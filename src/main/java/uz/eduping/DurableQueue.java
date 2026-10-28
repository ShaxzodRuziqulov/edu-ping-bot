package uz.eduping;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DurableQueue {
    private static final Logger LOG=LoggerFactory.getLogger(DurableQueue.class);
    private static final int MAX_ATTEMPTS=12;
    // Cosmetic calls: losing one must not count as a delivery error or hold the chat back for long.
    private static final Set<String> SOFT_METHODS=Set.of("answerCallbackQuery","setMessageReaction");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BotService bot;
    private final TelegramClient telegram;
    public DurableQueue(JdbcTemplate db,TransactionTemplate tx,BotService bot,TelegramClient telegram) { this.db=db;this.tx=tx;this.bot=bot;this.telegram=telegram; }

    public long offset() { return Objects.requireNonNull(db.queryForObject("SELECT next_offset FROM app_state WHERE id=1",Long.class)); }
    public void ingest(JsonNode updates) {
        if(!updates.isArray()) throw new IllegalArgumentException("Expected Telegram update array");
        tx.executeWithoutResult(status->{
            long next=offset();
            for(JsonNode update:updates) {
                if(!update.path("update_id").isIntegralNumber()) throw new IllegalArgumentException("Missing update_id");
                long id=update.path("update_id").asLong();
                long actor=update.path("message").path("from").path("id").asLong(update.path("callback_query").path("from").path("id").asLong());
                db.update("INSERT INTO inbox(update_id,payload,actor_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",id,Json.write(update),actor);
                next=Math.max(next,id+1);
            }
            db.update("UPDATE app_state SET next_offset=? WHERE id=1",next);
        });
    }

    public boolean processInbox() {
        Long[] selected={null};
        try {
            return Boolean.TRUE.equals(tx.execute(status->{
                var rows=db.queryForList("SELECT i.update_id,i.payload FROM inbox i WHERE i.state='PENDING' AND i.available_at<=now() AND NOT EXISTS (SELECT 1 FROM inbox older WHERE older.actor_id=i.actor_id AND older.state='PENDING' AND older.update_id<i.update_id) ORDER BY i.update_id LIMIT 1 FOR UPDATE OF i SKIP LOCKED");
                if(rows.isEmpty()) return false;
                var row=rows.get(0); selected[0]=((Number)row.get("update_id")).longValue();
                bot.handle(Json.read(row.get("payload").toString()));
                db.update("UPDATE inbox SET state='DONE',processed_at=now(),payload='' WHERE update_id=?",selected[0]);
                return true;
            }));
        } catch(Exception e) {
            if(selected[0]!=null) retryInbox(selected[0]);
            LOG.warn("Inbox processing delayed; update_id={}, type={}",selected[0],e.getClass().getSimpleName());
            return false;
        }
    }

    private void retryInbox(long id) {
        tx.executeWithoutResult(status->{
            var rows=db.queryForList("SELECT attempts,actor_id FROM inbox WHERE update_id=? AND state='PENDING' FOR UPDATE",id);
            if(rows.isEmpty()) return;
            int n=((Number)rows.get(0).get("attempts")).intValue()+1;
            boolean dead=n>=MAX_ATTEMPTS;
            db.update("UPDATE inbox SET attempts=?,state=?,available_at=?,error_code='PROCESSING_ERROR' WHERE update_id=?",n,dead?"FAILED":"PENDING",later(delay(n)),id);
            long actor=((Number)rows.get(0).get("actor_id")).longValue();
            // Tell the person once, so a silent bot is not mistaken for a received message.
            if(n==1 && actor>0) db.update("INSERT INTO outbox(job_type,chat_id,method,payload) VALUES ('API',?,'sendMessage',?)",actor,
                Json.write(Map.of("chat_id",actor,"text","⚠️ Texnik nosozlik. Xabaringiz saqlangan, bot uni qayta ishlashga urinmoqda.","disable_notification",true)));
        });
    }

    /** Sends one queued Telegram call. */
    public boolean deliverOne() { return runJob("API"); }
    /** Downloads one queued file into the archive; kept apart so slow downloads never hold up replies. */
    public boolean archiveOne() { return runJob("ARCHIVE"); }

    private boolean runJob(String type) {
        var picked=new AtomicReference<Map<String,Object>>();
        try {
            return Boolean.TRUE.equals(tx.execute(status->{
                // Preserve each chat's order, including when an earlier message waits for a retry.
                var rows=db.queryForList("SELECT o.* FROM outbox o WHERE o.job_type=? AND o.state='PENDING' AND o.available_at<=now() AND (o.chat_id IS NULL OR (NOT EXISTS (SELECT 1 FROM outbox older WHERE older.chat_id=o.chat_id AND older.state='PENDING' AND older.id<o.id) AND NOT EXISTS (SELECT 1 FROM delivery_clock d WHERE d.chat_id=o.chat_id AND d.sent_at>now()-interval '1 second'))) ORDER BY o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED",type);
                if(rows.isEmpty()) return false;
                var row=rows.get(0); picked.set(row);
                long id=((Number)row.get("id")).longValue();
                try {
                    if(type.equals("ARCHIVE")) archive(((Number)row.get("message_id")).longValue());
                    else {
                        telegram.call(row.get("method").toString(),Json.read(row.get("payload").toString()));
                        if(row.get("chat_id")!=null) db.update("INSERT INTO delivery_clock(chat_id,sent_at) VALUES (?,clock_timestamp()) ON CONFLICT(chat_id) DO UPDATE SET sent_at=excluded.sent_at",row.get("chat_id"));
                    }
                    db.update("UPDATE outbox SET state='SENT',sent_at=now(),payload=NULL,error_code=NULL WHERE id=?",id);
                } catch(TelegramClient.Failure failure) {
                    fail(row,((Number)row.get("attempts")).intValue()+1,failure.permanent(),failure.retryAfter,"TELEGRAM_"+failure.code);
                }
                return true;
            }));
        } catch(RuntimeException e) {
            var row=picked.get();
            if(row==null) throw e;
            // The transaction is gone; record the failure in a fresh one so this job cannot block the queue forever.
            tx.executeWithoutResult(status->{
                var current=db.queryForList("SELECT * FROM outbox WHERE id=? AND state='PENDING' FOR UPDATE",row.get("id"));
                if(!current.isEmpty()) fail(current.get(0),((Number)current.get(0).get("attempts")).intValue()+1,false,0,"INTERNAL");
            });
            LOG.warn("Outbox job {} failed internally: {}",row.get("id"),e.getClass().getSimpleName());
            return true;
        }
    }

    private void archive(long messageId) {
        String file=db.queryForObject("SELECT file_id FROM ticket_message WHERE id=?",String.class,messageId);
        byte[] bytes=telegram.download(file);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        db.update("UPDATE ticket_message SET file_bytes=?,sha256=?,archive_state='STORED',file_size=? WHERE id=?",bytes,hash,bytes.length,messageId);
    }

    private void fail(Map<String,Object> row,int attempt,boolean permanent,int retryAfter,String error) {
        long id=((Number)row.get("id")).longValue();
        boolean soft=SOFT_METHODS.contains(Objects.toString(row.get("method"),""));
        boolean dead=permanent || attempt>=(soft?3:MAX_ATTEMPTS);
        String state=dead?(soft?"DROPPED":"FAILED"):"PENDING";
        db.update("UPDATE outbox SET state=?,attempts=?,available_at=?,error_code=? WHERE id=?",state,attempt,later(Math.max(delay(attempt),retryAfter)),error,id);
        if(dead && row.get("message_id")!=null) db.update("UPDATE ticket_message SET archive_state='FAILED' WHERE id=?",row.get("message_id"));
        if(!soft) LOG.warn("Outbox {}: job={}, error={}, attempt={}",dead?"FAILED":"retry",id,error,attempt);
    }
    private static int delay(int attempt) { return (int)Math.min(3600,Math.pow(2,Math.min(attempt,12))); }
    private static Timestamp later(long seconds) { return Timestamp.from(Instant.now().plusSeconds(seconds)); }
}
