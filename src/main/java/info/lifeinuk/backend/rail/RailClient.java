package info.lifeinuk.backend.rail;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class RailClient {
    private final RailProperties properties;
    private final RailDepartureProperties departures;
    private final HttpClient client;
    private final Duration timeout;
    @Autowired RailClient(RailProperties properties, RailDepartureProperties departures) {
        this(properties, departures, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(10));
    }
    RailClient(RailProperties properties, HttpClient client, Duration timeout) { this(properties, new RailDepartureProperties("", ""), client, timeout); }
    RailClient(RailProperties properties, RailDepartureProperties departures, HttpClient client, Duration timeout) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Redirects must be disabled");
        this.properties = properties; this.departures = departures; this.client = client; this.timeout = timeout;
    }
    URI uri(String crs, Integer rows, Integer window) {
        String query = rows == null ? "" : "numRows=" + rows;
        if (window != null) query += (query.isEmpty() ? "" : "&") + "timeWindow=" + window;
        return URI.create(properties.baseUrl() + "/api/20220120/GetArrBoardWithDetails/" + crs + (query.isEmpty() ? "" : "?" + query));
    }
    byte[] arrivals(String crs, Integer rows, Integer window) { return request(properties, uri(crs, rows, window)); }
    byte[] departures(String crs, Integer rows, Integer window) {
        if (!departures.configured()) throw new RailFailure(RailFailure.Reason.NOT_CONFIGURED);
        RailProperties config;
        try { config = new RailProperties(departures.baseUrl(), departures.apiKey()); }
        catch (IllegalArgumentException invalid) { throw new RailFailure(RailFailure.Reason.NOT_CONFIGURED); }
        String query = rows == null ? "" : "numRows=" + rows;
        if (window != null) query += (query.isEmpty() ? "" : "&") + "timeWindow=" + window;
        return request(config, URI.create(config.baseUrl() + "/api/20220120/GetDepBoardWithDetails/" + crs + (query.isEmpty() ? "" : "?" + query)));
    }
    private byte[] request(RailProperties properties, URI uri) {
        if (!properties.configured()) throw new RailFailure(RailFailure.Reason.NOT_CONFIGURED);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("x-apikey", properties.apiKey())
                .header("Accept", "application/json").header("Accept-Encoding", "identity").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try { pending = client.sendAsync(req, info -> {
            int s = info.statusCode();
            if (s != 200) throw new RailFailure(s == 401 || s == 403 ? RailFailure.Reason.AUTHENTICATION : s == 429 ? RailFailure.Reason.RATE_LIMIT : s >= 400 && s < 500 ? RailFailure.Reason.UPSTREAM_REQUEST : RailFailure.Reason.UPSTREAM_ERROR);
            String contentType = info.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?") || info.headers().firstValueAsLong("Content-Length").orElse(0) > 1048576
                    || info.headers().allValues("Content-Encoding").stream().anyMatch(v -> !v.equalsIgnoreCase("identity"))) throw new RailFailure(RailFailure.Reason.INVALID_RESPONSE);
            return new LimitedBody();
        }); } catch (RuntimeException e) { throw new RailFailure(RailFailure.Reason.NETWORK); }
        try { return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body(); }
        catch (TimeoutException e) { pending.cancel(true); throw new RailFailure(RailFailure.Reason.TIMEOUT); }
        catch (InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw new RailFailure(RailFailure.Reason.NETWORK); }
        catch (ExecutionException e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof RailFailure failure) throw failure;
                if (c instanceof HttpTimeoutException) throw new RailFailure(RailFailure.Reason.TIMEOUT);
            }
            throw new RailFailure(RailFailure.Reason.NETWORK);
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
                if (b.remaining() > 1048576 - bytes.size()) { subscription.cancel(); body.completeExceptionally(new RailFailure(RailFailure.Reason.INVALID_RESPONSE)); return; }
                byte[] chunk = new byte[b.remaining()]; b.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable t) { body.completeExceptionally(t); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
