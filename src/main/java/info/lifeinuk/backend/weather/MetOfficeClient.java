package info.lifeinuk.backend.weather;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import static info.lifeinuk.backend.weather.WeatherFailure.Reason.*;

/**
 * One read-only Met Office Global spot {@code /point/hourly} request for a fixed city. The key travels only in
 * the {@code apikey} header; the URL carries the city's coordinates and fixed flags. Same transport rules as the
 * OS Names client: no redirects, identity encoding, JSON only, bounded body, one overall timeout.
 */
@Component
class MetOfficeClient {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    static final String USER_AGENT = "LifeInUK/0.0.1 (homepage weather)";
    private final HttpClient client;
    private final Duration timeout;
    private final MetOfficeProperties properties;

    @Autowired
    MetOfficeClient(MetOfficeProperties properties) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                RESPONSE_TIMEOUT, properties);
    }

    // Offline test seam only; the destination is always the canonical configured base.
    MetOfficeClient(HttpClient client, Duration timeout, MetOfficeProperties properties) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Redirects must be disabled");
        }
        this.client = client;
        this.timeout = timeout;
        this.properties = properties;
    }

    static URI hourlyUri(String baseUrl, WeatherCity city) {
        return URI.create(baseUrl + "/point/hourly?latitude=" + coordinate(city.latitude())
                + "&longitude=" + coordinate(city.longitude()) + "&excludeParameterMetadata=true&includeLocationName=false");
    }

    private static String coordinate(double value) { return String.format(Locale.ROOT, "%.4f", value); }

    boolean configured() { return properties.hasUsableApiKey(); }

    byte[] hourly(WeatherCity city) {
        if (!properties.hasUsableApiKey()) {
            throw new WeatherFailure(NOT_CONFIGURED);
        }
        HttpRequest request = HttpRequest.newBuilder(hourlyUri(properties.baseUrl(), city)).timeout(timeout)
                .header("apikey", properties.apiKey()).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("User-Agent", USER_AGENT).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client.sendAsync(request, info -> {
                validate(info);
                return new LimitedBody();
            });
        } catch (RuntimeException failure) {
            throw new WeatherFailure(UNAVAILABLE);
        }
        try {
            // Covers headers and the whole body, including a stalled body.
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new WeatherFailure(UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new WeatherFailure(UNAVAILABLE);
        } catch (ExecutionException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof WeatherFailure failure) { throw failure; }
            }
            throw new WeatherFailure(UNAVAILABLE);
        }
    }

    private static void validate(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            throw new WeatherFailure(UNAVAILABLE);
        }
        List<String> types = info.headers().allValues("Content-Type");
        if (types.size() != 1 || types.getFirst().length() > 200) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        try {
            MediaType type = MediaType.parseMediaType(types.getFirst());
            boolean json = "application".equalsIgnoreCase(type.getType())
                    && ("json".equalsIgnoreCase(type.getSubtype()) || type.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
            if (!json) {
                throw new WeatherFailure(INVALID_RESPONSE);
            }
        } catch (IllegalArgumentException invalid) {
            throw new WeatherFailure(INVALID_RESPONSE);
        }
        if (info.headers().allValues("Content-Encoding").stream().anyMatch(value -> !"identity".equalsIgnoreCase(value))
                || info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BODY_BYTES) {
            throw new WeatherFailure(INVALID_RESPONSE);
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
                    body.completeExceptionally(new WeatherFailure(INVALID_RESPONSE));
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
