package info.lifeinuk.backend.places;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import static info.lifeinuk.backend.places.PlaceSearchFailure.Reason.*;

/**
 * One read-only OS Names {@code /find} request per search. The key travels only in the {@code key} header;
 * the URL carries the free-text query, a bounded result count and the place-type filter.
 */
@Component
class OsNamesClient {
    static final int MAX_RESULTS = 10;
    static final int MAX_BODY_BYTES = 512 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    /** Documented fq LOCAL_TYPE values for postcodes and populated places only (no roads, POIs or landforms). */
    static final List<String> LOCAL_TYPES = List.of("Postcode", "City", "Town", "Village", "Hamlet",
            "Suburban_Area", "Other_Settlement");
    static final String USER_AGENT = "LifeInUK/0.0.1 (place search)";
    private final HttpClient client;
    private final Duration timeout;
    private final OsNamesProperties properties;

    @Autowired
    OsNamesClient(OsNamesProperties properties) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                RESPONSE_TIMEOUT, properties);
    }

    // Offline test seam only; the destination is always the canonical configured base.
    OsNamesClient(HttpClient client, Duration timeout, OsNamesProperties properties) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Redirects must be disabled");
        }
        this.client = client;
        this.timeout = timeout;
        this.properties = properties;
    }

    static URI findUri(String baseUrl, String query) {
        String filter = String.join(" ", LOCAL_TYPES.stream().map(type -> "LOCAL_TYPE:" + type).toList());
        return URI.create(baseUrl + "/find?query=" + encode(query) + "&maxresults=" + MAX_RESULTS
                + "&fq=" + encode(filter) + "&format=JSON");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    byte[] find(String query) {
        if (!properties.hasUsableApiKey()) {
            throw new PlaceSearchFailure(NOT_CONFIGURED);
        }
        HttpRequest request = HttpRequest.newBuilder(findUri(properties.baseUrl(), query)).timeout(timeout)
                .header("key", properties.apiKey()).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("User-Agent", USER_AGENT).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client.sendAsync(request, info -> {
                validate(info);
                return new LimitedBody();
            });
        } catch (RuntimeException failure) {
            throw new PlaceSearchFailure(UNAVAILABLE);
        }
        try {
            // Covers headers and the whole body, including a stalled body.
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new PlaceSearchFailure(UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new PlaceSearchFailure(UNAVAILABLE);
        } catch (ExecutionException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof PlaceSearchFailure failure) { throw failure; }
            }
            throw new PlaceSearchFailure(UNAVAILABLE);
        }
    }

    private static void validate(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            throw new PlaceSearchFailure(UNAVAILABLE);
        }
        List<String> types = info.headers().allValues("Content-Type");
        if (types.size() != 1 || types.getFirst().length() > 200) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
        try {
            MediaType type = MediaType.parseMediaType(types.getFirst());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) {
                throw new PlaceSearchFailure(INVALID_RESPONSE);
            }
        } catch (IllegalArgumentException invalid) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
        if (info.headers().allValues("Content-Encoding").stream().anyMatch(value -> !"identity".equalsIgnoreCase(value))
                || info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BODY_BYTES) {
            throw new PlaceSearchFailure(INVALID_RESPONSE);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new PlaceSearchFailure(INVALID_RESPONSE));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
