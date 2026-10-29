package uz.eduping;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="app.workers-enabled",havingValue="true",matchIfMissing=true)
public class Workers {
    private static final Logger LOG=LoggerFactory.getLogger(Workers.class);
    private final DurableQueue queue;
    private final TelegramClient telegram;
    private final DataSource datasource;
    private final BotProperties config;
    public Workers(DurableQueue queue,TelegramClient telegram,DataSource datasource,BotProperties config) {
        if(config.token().isBlank()) throw new IllegalStateException("Set TELEGRAM_BOT_TOKEN before enabling workers");
        if(config.setupMode()) LOG.warn("TEACHER_IDS is not set: setup mode, the bot only answers with the user's Telegram ID");
        this.queue=queue;this.telegram=telegram;this.datasource=datasource;this.config=config;
    }

    /** Fills Telegram's "/" menu: student commands for everyone, teacher commands only in teachers' chats. */
    @EventListener(ApplicationReadyEvent.class) public void registerCommands() {
        var student=BotCommands.commands(false,"uz");
        try { telegram.call("setMyCommands",Map.of("commands",student)); }
        catch(TelegramClient.Failure e) { LOG.warn("Could not register commands: HTTP {}",e.code); }
        var db=new JdbcTemplate(datasource);
        for(long id:config.teachers()) {
            var languages=db.queryForList("SELECT language FROM app_user WHERE id=?",String.class,id);
            String language=languages.isEmpty()?"uz":languages.get(0);
            try { telegram.call("setMyCommands",Map.of("commands",BotCommands.commands(true,language),"scope",Map.of("type","chat","chat_id",id))); }
            catch(TelegramClient.Failure e) { LOG.warn("Could not register teacher commands (teacher must /start the bot first): HTTP {}",e.code); }
        }
    }

    // getUpdates itself waits up to 25 s for news, so the short delay only matters between batches.
    @Scheduled(fixedDelay=200) public void poll() {
        // Session lock prevents simultaneous getUpdates calls even if a second process is started accidentally.
        try(var connection=datasource.getConnection();var stmt=connection.createStatement()) {
            try(var result=stmt.executeQuery("SELECT pg_try_advisory_lock(726341902)")) { result.next(); if(!result.getBoolean(1)) { pause(5000); return; } }
            try { queue.ingest(telegram.call("getUpdates",Map.of("offset",queue.offset(),"timeout",25,"limit",100,"allowed_updates",List.of("message","callback_query")))); }
            finally { stmt.execute("SELECT pg_advisory_unlock(726341902)"); }
        } catch(Exception e) {
            LOG.warn("Telegram polling delayed: {}",e instanceof TelegramClient.Failure f?"HTTP "+f.code:e.getClass().getSimpleName());
            pause(3000);
        }
    }
    @Scheduled(fixedDelay=100) public void inbox() {
        try { for(int i=0;i<30 && queue.processInbox();i++) { /* bounded batch */ } }
        catch(Exception e) { LOG.warn("Inbox worker delayed: {}",e.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=150) public void outbox() {
        try { for(int i=0;i<25 && queue.deliverOne();i++) { /* bounded batch */ } }
        catch(Exception e) { LOG.warn("Outbox worker delayed: {}",e.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=1000) public void archive() {
        try { for(int i=0;i<5 && queue.archiveOne();i++) { /* bounded batch */ } }
        catch(Exception e) { LOG.warn("Archive worker delayed: {}",e.getClass().getSimpleName()); }
    }
    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
