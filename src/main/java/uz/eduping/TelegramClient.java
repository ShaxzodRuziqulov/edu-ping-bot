package uz.eduping;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.Map;

@Component
public class TelegramClient {
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private final BotProperties config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public TelegramClient(BotProperties config) { this.config = config; }
    public JsonNode call(String method, Object payload) {
        try {
            var request = HttpRequest.newBuilder(URI.create(config.telegramBaseUrl() + "/bot" + config.token() + "/" + method))
                .timeout(Duration.ofSeconds(method.equals("getUpdates") ? 40 : 15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try { json = Json.read(response.body()); }
            catch (Exception e) { throw new Failure(response.statusCode(), 0); }
            if (response.statusCode() != 200 || !json.path("ok").asBoolean())
                throw new Failure(json.path("error_code").asInt(response.statusCode()), json.path("parameters").path("retry_after").asInt());
            return json.path("result");
        } catch (Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(599, 0); }
        catch (Exception e) { throw new Failure(599, 0); } // Never log exception URLs: they contain the bot token.
    }
    public byte[] download(String fileId) {
        var file = call("getFile", Map.of("file_id", fileId));
        String path = file.path("file_path").asText();
        if (file.path("file_size").asLong() > MAX_FILE_BYTES) throw new Failure(413, 0);
        if (path.isBlank() || !path.matches("[A-Za-z0-9_./-]+") || path.contains("..")) throw new Failure(502, 0);
        try {
            var request = HttpRequest.newBuilder(URI.create(config.telegramBaseUrl() + "/file/bot" + config.token() + "/" + path))
                .timeout(Duration.ofSeconds(30)).GET().build();
            var future=http.sendAsync(request,info -> new LimitedBody(MAX_FILE_BYTES));
            HttpResponse<byte[]> response;
            try { response=future.get(30,TimeUnit.SECONDS); }
            catch(TimeoutException e) { future.cancel(true); throw new Failure(599,0); }
            catch(ExecutionException e) {
                Throwable cause=e;
                while(cause.getCause()!=null) cause=cause.getCause();
                if(cause instanceof Failure failure) throw failure;
                throw new Failure(599,0);
            }
            if(response.statusCode()!=200) throw new Failure(response.statusCode(),0);
            return response.body();
        } catch (Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(599, 0); }
        catch (Exception e) { throw new Failure(599, 0); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>limit-bytes.size()) { subscription.cancel();result.completeExceptionally(new Failure(413,0));return; }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    public static final class Failure extends RuntimeException {
        public final int code;
        public final int retryAfter;
        public Failure(int code, int retryAfter) { super("Telegram HTTP " + code); this.code = code; this.retryAfter = retryAfter; }
        public boolean permanent() { return code == 400 || code == 401 || code == 403 || code == 404 || code == 413; }
    }
}
