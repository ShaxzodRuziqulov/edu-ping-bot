package uz.eduping;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55439/eduping_test}","spring.datasource.username=${TEST_DATABASE_USER:testbot}","spring.datasource.password=${TEST_DATABASE_PASSWORD:test-only-local}","app.workers-enabled=false","app.token=TEST","app.teacher-ids=1001,1002","app.telegram-base-url=${TEST_TELEGRAM_BASE_URL:http://127.0.0.1:4901/telegram}","app.bot-username=EduPingBot"})
class BotIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired DurableQueue queue;
    @Autowired TelegramClient telegram;
    @Autowired BotProperties properties;
    private long sequence;
    private final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void reset() throws Exception {
        try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL().endsWith("/eduping_test"),"Refusing to truncate a non-test database");
        }
        db.execute("TRUNCATE outbox,ticket_message,chat_session,ticket,membership,classroom,app_user,inbox,delivery_clock,join_attempt,audit_log RESTART IDENTITY CASCADE");
        db.update("UPDATE app_state SET next_offset=0"); sequence=0;
        control("/__reset",Map.of());
    }
    private void control(String path,Object body) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(properties.telegramBaseUrl()+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
    }
    private JsonNode calls() throws Exception { return Json.read(http.send(HttpRequest.newBuilder(URI.create(properties.telegramBaseUrl()+"/__calls")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()); }
    @SuppressWarnings("unchecked") private JsonNode update(long actor,Object fields) {
        var message=new HashMap<String,Object>(); message.put("message_id",sequence+1);message.put("chat",Map.of("id",actor,"type","private")); message.put("from",Map.of("id",actor,"first_name","User "+actor,"is_bot",false));
        if(fields instanceof String text) message.put("text",text); else message.putAll((Map<String,Object>)fields);
        return Json.read(Json.write(Map.of("update_id",++sequence,"message",message)));
    }
    private JsonNode callback(long actor,String data) {
        var query=Map.of("id","cb"+(sequence+1),"from",Map.of("id",actor,"first_name","User "+actor,"is_bot",false),"message",Map.of("message_id",1,"chat",Map.of("id",actor,"type","private")),"data",data);
        return Json.read(Json.write(Map.of("update_id",++sequence,"callback_query",query)));
    }
    // Existing workflow tests model users who have already chosen Uzbek.
    private void existingUser(long actor) { db.update("INSERT INTO app_user(id,display_name,language) VALUES (?,?,'uz') ON CONFLICT DO NOTHING",actor,"User "+actor); }
    private void send(long actor,Object fields) { existingUser(actor); rawSend(actor,fields); }
    private void rawSend(long actor,Object fields) { queue.ingest(Json.read("["+update(actor,fields)+"]")); assertTrue(queue.processInbox()); }
    private void tap(long actor,String data) { existingUser(actor); queue.ingest(Json.read("["+callback(actor,data)+"]")); assertTrue(queue.processInbox()); }
    private long count(String sql) { return Objects.requireNonNull(db.queryForObject(sql,Long.class)); }
    private String code() { return db.queryForObject("SELECT invite_code FROM classroom WHERE id=1",String.class); }
    private void clearOutbox() { db.update("DELETE FROM outbox"); db.update("DELETE FROM delivery_clock"); }
    private String pendingText() { return String.join("\n",db.queryForList("SELECT payload FROM outbox WHERE method='sendMessage' ORDER BY id",String.class)); }
    private String sentTo(long chat) { return String.join("\n",db.queryForList("SELECT payload FROM outbox WHERE chat_id=? ORDER BY id",String.class,chat)); }
    /** Teacher creates 7-A; student joins through the invite link and introduces themself. */
    private void onboard() { send(1001,"/class 7-A");send(2001,"/start "+code());send(2001,"Ali Valiyev"); }
    /** …and asks the first question by tapping the menu button. */
    private void ask() { onboard();send(2001,BotService.NEW);send(2001,"Masalani tushuntirib bering"); }

    @Test void guidedWorkflowNeedsNoTypedNumbers() {
        ask();
        assertEquals("Ali Valiyev",db.queryForObject("SELECT full_name FROM app_user WHERE id=2001",String.class));
        assertEquals("Masalani tushuntirib bering",db.queryForObject("SELECT subject FROM ticket WHERE id=1",String.class));
        assertTrue(sentTo(1001).contains("Ali Valiyev")&&sentTo(1001).contains("\"r:1\""),"teacher gets the question with a reply button");
        assertTrue(sentTo(1001).contains("\"disable_notification\":false"),"a new question rings the teacher's phone");
        assertEquals(1,count("SELECT count(*) FROM outbox WHERE method='setMessageReaction' AND chat_id=2001"));
        tap(1001,"r:1");send(1001,"Avval qavsni oching");
        assertTrue(sentTo(2001).contains("Ustoz javobi")&&sentTo(2001).contains("Avval qavsni oching")&&sentTo(2001).contains("\"x:1\""));
        assertEquals(2,count("SELECT count(*) FROM ticket_message"));
        tap(1001,"c:1");
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM ticket WHERE id=1",String.class));
        assertEquals(0,count("SELECT count(*) FROM chat_session"));
        send(2001,"/continue 1");assertTrue(pendingText().contains("yopilgan"));
    }
    @Test void firstContactRequiresExplicitLanguageAndKeepsInvite() {
        send(1001,"/class 7-A");
        clearOutbox();
        rawSend(2001,"/start "+code());
        assertNull(db.queryForObject("SELECT language FROM app_user WHERE id=2001",String.class));
        assertEquals(0,count("SELECT count(*) FROM membership"));
        assertTrue(sentTo(2001).contains("Выберите язык"));
        tap(2001,"lang:ru");
        assertEquals("ru",db.queryForObject("SELECT language FROM app_user WHERE id=2001",String.class));
        assertEquals(1,count("SELECT count(*) FROM membership"));
        assertTrue(sentTo(2001).contains("имя и фамилию"));
        clearOutbox();rawSend(2001,"Иван <b>Иванов</b>");
        assertTrue(sentTo(2001).contains("Спасибо"));
        assertTrue(sentTo(2001).contains("✍️ Новый вопрос"));
        assertTrue(sentTo(2001).contains("Иван &lt;b&gt;Иванов&lt;/b&gt;"));
    }
    @Test void firstContactCanChooseUzbekAndInvalidChoiceDoesNotGrantAccess() {
        rawSend(1001,"/class Secret");tap(1001,"lang:en");
        assertEquals(0,count("SELECT count(*) FROM classroom"));
        assertNull(db.queryForObject("SELECT language FROM app_user WHERE id=1001",String.class));
        tap(1001,"lang:uz");clearOutbox();send(1001,BotService.ADD_CLASS);send(1001,"7-A");
        assertTrue(sentTo(1001).contains("Sinf yaratildi"));
    }
    @Test void languageSurvivesReconstructionAndSwitchKeepsDraft() {
        onboard();send(2001,BotService.NEW);
        send(2001,"/language");tap(2001,"lang:ru");
        assertNull(db.queryForObject("SELECT ticket_id FROM chat_session WHERE user_id=2001",Long.class));
        assertEquals("WRITE",db.queryForObject("SELECT mode FROM chat_session WHERE user_id=2001",String.class));
        clearOutbox();var restarted=new BotService(db,properties);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()))
                .executeWithoutResult(s->restarted.handle(update(2001,"Savol yuborilmadi.")));
        assertEquals("Savol yuborilmadi.",db.queryForObject("SELECT body FROM ticket_message",String.class));
        assertTrue(sentTo(2001).contains("Ваш вопрос"));
        assertTrue(sentTo(1001).contains("Savol yuborilmadi."),"user content stays unchanged even when it matches a UI string");
        send(2001,"✅ Готово");assertEquals(0,count("SELECT count(*) FROM chat_session"));
        send(2001,"🌐 Til / Язык");tap(2001,"lang:uz");clearOutbox();send(2001,BotService.MY);
        assertTrue(sentTo(2001).contains("Savollaringiz"));
    }
    @Test void notificationsUseRecipientsLanguageInBothDirections() {
        onboard();tap(2001,"lang:ru");clearOutbox();send(2001,"✍️ Новый вопрос");send(2001,"Почему 2 < 3?");
        assertTrue(sentTo(1001).contains("✍️ Javob berish"));
        assertFalse(sentTo(1001).contains("✍️ Ответить"));
        tap(1001,"r:1");send(1001,"Chunki 2 kichik.");
        assertTrue(sentTo(2001).contains("Ответ учителя"));
        assertTrue(sentTo(2001).contains("Chunki 2 kichik."));
        assertTrue(sentTo(2001).contains("✅ Решено"));
        clearOutbox();tap(1001,"c:1");
        assertTrue(sentTo(2001).contains("Учитель закрыл вопрос"));
        assertTrue(sentTo(1001).contains("yopildi"));
    }
    @Test void russianTeacherSeesLocalizedClassesListsHistoryAndErrors() {
        ask();tap(1001,"lang:ru");clearOutbox();
        send(1001,"🏫 Классы");tap(1001,"cl:1");send(1001,"📥 Вопросы");tap(1001,"v:1");send(1001,"📊 Статистика");send(1001,"/help");send(1001,"/privacy");
        String text=sentTo(1001);
        assertTrue(text.contains("Ваши классы") && text.contains("Ссылка-приглашение"));
        assertTrue(text.contains("Открытые вопросы") && text.contains("Статистика"));
        assertTrue(text.contains("Для учителя") && text.contains("Конфиденциальность"));
        assertTrue(text.contains("Masalani tushuntirib bering"));
        clearOutbox();send(1001,"/view nope");send(1001,"/view 999");
        assertTrue(sentTo(1001).contains("Неверный номер"));assertTrue(sentTo(1001).contains("нет доступа"));
        clearOutbox();tap(1001,"rmy:1:2001");
        assertTrue(sentTo(2001).contains("chiqarildingiz"));
        assertTrue(sentTo(1001).contains("исключён"));
    }
    @Test void russianStudentErrorsHistoryAndRemovalStayRussian() {
        ask();tap(2001,"lang:ru");clearOutbox();
        send(2001,"/privacy");send(2001,"/help");send(2001,"/view 1");
        send(2001,Map.of("video",Map.of("file_id","large","file_size",TelegramClient.MAX_FILE_BYTES+1)));
        assertTrue(sentTo(2001).contains("20 МБ"));assertTrue(sentTo(2001).contains("Конфиденциальность"));
        assertTrue(sentTo(2001).contains("Для ученика"));assertTrue(sentTo(2001).contains("👤 Вы"));
        clearOutbox();tap(1001,"rmy:1:2001");assertTrue(sentTo(2001).contains("исключены из класса"));
    }
    @Test void switchingLanguageDoesNotDiscardNameOrClassEntry() {
        rawSend(1001,"/start");tap(1001,"lang:ru");send(1001,"➕ Новый класс");
        send(1001,"/lang");tap(1001,"lang:uz");send(1001,"7-A");
        rawSend(2001,code());tap(2001,"lang:uz");
        send(2001,"/language");tap(2001,"lang:ru");send(2001,"Иван Иванов");
        assertEquals("Иван Иванов",db.queryForObject("SELECT full_name FROM app_user WHERE id=2001",String.class));
        assertEquals(1,count("SELECT count(*) FROM membership"));
    }
    @Test void selectedCommandMenuIsDeliveredOverHttp() throws Exception {
        rawSend(2001,"/start");tap(2001,"lang:ru");
        while(queue.deliverOne()) db.update("DELETE FROM delivery_clock");
        assertTrue(calls().toString().contains("setMyCommands"));
        assertTrue(calls().toString().contains("Выбрать язык"));
        assertEquals(0,count("SELECT count(*) FROM outbox WHERE state IN ('PENDING','FAILED')"));
    }
    @Test void localizationDoesNotRequireAnExistingUserForFallbackNames() {
        send(1001,"/class 7-A");tap(1001,"lang:ru");clearOutbox();
        tap(1001,"rm:1:9999");
        assertTrue(sentTo(1001).contains("исключить"));
        assertEquals(0,count("SELECT count(*) FROM inbox WHERE state='FAILED'"));
    }
    @Test void teacherCreatesClassFromMenuAndGetsInviteLink() {
        send(1001,BotService.ADD_CLASS);send(1001,"8-B");
        assertEquals("8-B",db.queryForObject("SELECT name FROM classroom",String.class));
        assertTrue(pendingText().contains("https://t.me/EduPingBot?start="+code()));
        assertEquals(0,count("SELECT count(*) FROM chat_session"));
    }
    @Test void legacySlashCommandsStillWork() {
        onboard();send(2001,"/new 1 Algebra");send(2001,"Savol");send(1001,"/reply 1");send(1001,"Javob");send(1001,"/close 1");
        assertEquals("Algebra",db.queryForObject("SELECT subject FROM ticket",String.class));
        assertEquals(2,count("SELECT count(*) FROM ticket_message"));assertEquals("CLOSED",db.queryForObject("SELECT status FROM ticket",String.class));
    }
    @Test void duplicateUpdatesDoNotDuplicateBusinessWrites() {
        ask(); var update=update(2001,"Bir marta"); var batch=Json.read("["+update+"]");
        queue.ingest(batch);queue.ingest(batch);assertTrue(queue.processInbox());assertFalse(queue.processInbox());
        assertEquals(2,count("SELECT count(*) FROM ticket_message"));assertEquals(sequence+1,queue.offset());
    }
    @Test void invalidBatchRollsBackInboxAndOffset() {
        var update=update(2001,"/start");
        assertThrows(IllegalArgumentException.class,()->queue.ingest(Json.read("["+update+",{}]")));
        assertEquals(0,count("SELECT count(*) FROM inbox"));assertEquals(0,queue.offset());
    }
    @Test void otherStudentsAndTeachersCannotAccessTicket() {
        ask();clearOutbox();send(2002,"/view 1");send(1002,"/reply 1");tap(1002,"c:1");
        assertFalse(pendingText().contains("Masalani"));assertTrue(pendingText().contains("kirish huquqingiz yo‘q"));
        assertEquals(0,count("SELECT count(*) FROM chat_session WHERE user_id=1002"));assertEquals("OPEN",db.queryForObject("SELECT status FROM ticket",String.class));
    }
    @Test void teacherRoleCannotBeSelfGranted() {
        send(2001,"/class Hujum");tap(2001,"nc");assertEquals(0,count("SELECT count(*) FROM classroom"));assertTrue(pendingText().contains("faqat ustoz"));
    }
    @Test void removalNeedsConfirmationClosesTicketsAndKeepsOwnHistoryReadable() {
        ask();tap(1001,"rm:1:2001");assertEquals(1,count("SELECT count(*) FROM membership"));
        tap(1001,"rmy:1:2001");
        assertEquals(0,count("SELECT count(*) FROM membership"));assertEquals("CLOSED",db.queryForObject("SELECT status FROM ticket",String.class));
        assertTrue(sentTo(2001).contains("chiqarildingiz"));
        send(2001,"/continue 1");send(2001,"Yangi xabar");assertEquals(1,count("SELECT count(*) FROM ticket_message"));
        clearOutbox();send(2001,"/view 1");assertTrue(pendingText().contains("Masalani tushuntirib bering"));
    }
    @Test void rotatingInviteNeedsConfirmationAndInvalidatesOldCode() {
        send(1001,"/class 7-A");String previous=code();tap(1001,"k:1");assertEquals(previous,code());tap(1001,"ky:1");
        send(2001,"/join "+previous);assertEquals(0,count("SELECT count(*) FROM membership"));
        send(2001,"/join "+code());assertEquals(1,count("SELECT count(*) FROM membership"));
    }
    @Test void studentCanJoinByTypingCode() {
        send(1001,"/class 7-A");send(2001,"/start");send(2001,code());
        assertEquals(1,count("SELECT count(*) FROM membership"));assertTrue(pendingText().contains("ism va familiyangizni"));
    }
    @Test void draftSurvivesServiceReconstruction() {
        onboard();send(2001,BotService.NEW);clearOutbox();var reconstructed=new BotService(db,properties);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())).executeWithoutResult(s->reconstructed.handle(update(2001,"Qayta ishga tushgandan keyin")));
        assertEquals(1,count("SELECT count(*) FROM ticket"));assertEquals(1,count("SELECT count(*) FROM ticket_message"));
    }
    @Test void pressingNewQuestionByMistakeLeavesNoEmptyTicket() {
        onboard();send(2001,BotService.NEW);send(2001,BotService.DONE);
        assertEquals(0,count("SELECT count(*) FROM ticket"));assertTrue(pendingText().contains("Savol yuborilmadi"));
    }
    @Test void messageOutsideAQuestionIsNotSilentlyLost() {
        onboard();send(2001,"Salom ustoz");
        assertEquals(0,count("SELECT count(*) FROM ticket_message"));assertTrue(pendingText().contains("hech kimga yuborilmadi"));
    }
    @Test void studentClosesOwnQuestionAndTeacherIsTold() {
        ask();tap(2001,"x:1");
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM ticket",String.class));assertTrue(sentTo(1001).contains("hal bo‘ldi"));
    }
    @Test void userTextIsEscapedInFormattedMessages() {
        send(1001,"/class 7-A");send(2001,"/start "+code());send(2001,"Ali <b>Valiyev</b>");send(2001,BotService.NEW);send(2001,"1 < 2 & 3 > 2");
        assertTrue(sentTo(1001).contains("Ali &lt;b&gt;Valiyev&lt;/b&gt;"));assertTrue(sentTo(1001).contains("1 &lt; 2 &amp; 3 &gt; 2"));
    }
    @Test void setupModeOnlyTellsTheTelegramId() {
        existingUser(5005);
        var unconfigured=new BotService(db,new BotProperties(false,"TEST","0",properties.telegramBaseUrl(),"EduPingBot","Asia/Tashkent"));
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())).executeWithoutResult(s->unconfigured.handle(update(5005,"/class Hujum")));
        assertEquals(0,count("SELECT count(*) FROM classroom"));assertTrue(pendingText().contains("sozlanmagan")&&pendingText().contains("5005"));
    }
    @Test void mediaIsArchivedAsBytesWithChecksum() throws Exception {
        onboard();send(2001,BotService.NEW);clearOutbox();send(2001,Map.of("document",Map.of("file_id","test-doc","file_unique_id","unique","file_name","homework.pdf","mime_type","application/pdf","file_size",24),"caption","Uy vazifa"));
        assertEquals("Uy vazifa",db.queryForObject("SELECT subject FROM ticket",String.class));
        assertTrue(queue.archiveOne());
        assertEquals("STORED",db.queryForObject("SELECT archive_state FROM ticket_message",String.class));
        assertArrayEquals("eduping-archive-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8),db.queryForObject("SELECT file_bytes FROM ticket_message",byte[].class));
        assertEquals(64,Objects.requireNonNull(db.queryForObject("SELECT sha256 FROM ticket_message",String.class)).length());
        while(queue.deliverOne()) db.update("DELETE FROM delivery_clock");
        assertTrue(calls().toString().contains("sendDocument"));
        assertEquals(0,count("SELECT count(*) FROM outbox WHERE state IN ('FAILED','PENDING')"));
    }
    @Test void oversizedAttachmentIsNotAccepted() {
        onboard();send(2001,BotService.NEW);send(2001,Map.of("video",Map.of("file_id","large","file_size",TelegramClient.MAX_FILE_BYTES+1)));
        assertEquals(0,count("SELECT count(*) FROM ticket_message"));assertTrue(pendingText().contains("20 MB dan katta"));
    }
    @Test void transientHtmlErrorRetriesActualHttpCallWithoutLosingJob() throws Exception {
        send(2001,"/id");control("/__fail",Map.of("method","sendMessage","code",502,"html",true));
        assertTrue(queue.deliverOne());assertEquals(1,count("SELECT attempts FROM outbox"));assertEquals(1,count("SELECT count(*) FROM outbox WHERE state='PENDING'"));
        db.update("UPDATE outbox SET available_at=now()-interval '1 second'");assertTrue(queue.deliverOne());assertEquals(1,count("SELECT count(*) FROM outbox WHERE state='SENT'"));assertEquals(2,calls().size());
    }
    @Test void rateLimitHonorsTelegramRetryAfter() throws Exception {
        send(2001,"/id");control("/__fail",Map.of("code",429,"retry_after",17));queue.deliverOne();
        assertTrue(Objects.requireNonNull(db.queryForObject("SELECT extract(epoch FROM available_at-now())>15 FROM outbox",Boolean.class)));assertEquals("PENDING",db.queryForObject("SELECT state FROM outbox",String.class));
    }
    @Test void permanentFailureGoesToVisibleFailedQueue() throws Exception {
        send(2001,"/id");control("/__fail",Map.of("code",403));queue.deliverOne();
        assertEquals("FAILED",db.queryForObject("SELECT state FROM outbox",String.class));assertEquals("TELEGRAM_403",db.queryForObject("SELECT error_code FROM outbox",String.class));
    }
    @Test void failedReactionIsDroppedNotReportedAsDeliveryError() throws Exception {
        db.update("INSERT INTO outbox(job_type,chat_id,method,payload) VALUES ('API',7,'setMessageReaction','{\"chat_id\":7}')");
        assertTrue(queue.deliverOne());
        assertEquals("DROPPED",db.queryForObject("SELECT state FROM outbox",String.class));assertEquals(0,count("SELECT count(*) FROM outbox WHERE state='FAILED'"));
    }
    @Test void brokenJobDoesNotBlockTheQueue() {
        db.update("INSERT INTO outbox(job_type,chat_id,method,payload) VALUES ('API',5,'sendMessage','not json')");
        db.update("INSERT INTO outbox(job_type,chat_id,method,payload) VALUES ('API',6,'sendMessage',?)",Json.write(Map.of("chat_id",6,"text","salom")));
        assertTrue(queue.deliverOne());assertTrue(queue.deliverOne());
        assertEquals("INTERNAL",db.queryForObject("SELECT error_code FROM outbox WHERE chat_id=5",String.class));
        assertEquals(1,count("SELECT attempts FROM outbox WHERE chat_id=5"));
        assertEquals("SENT",db.queryForObject("SELECT state FROM outbox WHERE chat_id=6",String.class));
    }
    @Test void retryMaintainsPerChatMessageOrder() throws Exception {
        send(2001,"/id");send(2001,"/privacy");control("/__fail",Map.of("code",500));queue.deliverOne();
        assertFalse(queue.deliverOne());assertEquals(1,calls().size());
    }
    @Test void pollingHttpAdapterReturnsUpdatesWhichCommitBeforeOffset() throws Exception {
        var update=update(2001,"/start");control("/__updates",Map.of("updates",List.of(update)));
        queue.ingest(telegram.call("getUpdates",Map.of("offset",0,"timeout",0,"allowed_updates",List.of("message","callback_query"))));
        assertEquals(2,queue.offset());assertEquals(1,count("SELECT count(*) FROM inbox WHERE state='PENDING'"));assertTrue(queue.processInbox());
    }
    @Test void openQuestionLimitAndUnknownContentGiveUsefulErrors() {
        onboard();
        for(int i=0;i<BotService.MAX_OPEN_TICKETS;i++) { send(2001,BotService.NEW);send(2001,"Savol "+i);send(2001,BotService.DONE); }
        send(2001,BotService.NEW);assertTrue(pendingText().contains(BotService.MAX_OPEN_TICKETS+" ta ochiq savol"));
        tap(2001,"x:1");send(2001,BotService.NEW);
        send(2001,Map.of("location",Map.of("latitude",41.3,"longitude",69.2)));
        assertEquals(BotService.MAX_OPEN_TICKETS,count("SELECT count(*) FROM ticket"));assertTrue(pendingText().contains("qabul qilinmaydi"));
    }
    @Test void teacherInboxListsWaitingQuestionsFirst() {
        ask();send(2001,BotService.DONE);send(2001,BotService.NEW);send(2001,"Ikkinchi savol");
        tap(1001,"r:1");send(1001,"Javob");clearOutbox();send(1001,BotService.INBOX);
        String inbox=pendingText();
        assertTrue(inbox.indexOf("#2")<inbox.indexOf("#1"),"unanswered #2 comes before answered #1");
    }
    @Test void delayedInboxDoesNotLetSameActorsMessagesOvertake() {
        queue.ingest(Json.read("["+update(2001,"/start")+","+update(2001,"/id")+","+update(2002,"/start")+"]"));
        db.update("UPDATE inbox SET available_at=now()+interval '1 hour' WHERE update_id=1");
        assertTrue(queue.processInbox());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM inbox WHERE update_id=2",String.class));
        assertEquals("DONE",db.queryForObject("SELECT state FROM inbox WHERE update_id=3",String.class));
        assertFalse(queue.processInbox());
    }
}
