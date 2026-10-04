package info.lifeinuk.backend.acquisition;

import info.lifeinuk.backend.source.SourceEndpoint;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;

/** Package-private bounded JSON transport shared by the controlled acquisitions. */
final class JsonEvidenceHttp {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Unreserved and query-delimiter characters only: no encoding, spaces, '?', '#' or '%' escapes. */
    private static final Pattern SAFE_QUERY = Pattern.compile("[A-Za-z0-9._~:=&-]{1,2000}");
    private static final Pattern CREDENTIAL_PARAMETER =
            Pattern.compile("subscription-key|ocp-apim|api[-_]?key", Pattern.CASE_INSENSITIVE);
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);
    private final String userAgent;
    private final HttpClient client;
    private final Duration timeout;

    JsonEvidenceHttp(String userAgent) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), RESPONSE_TIMEOUT, userAgent);
    }

    // Package-private seam for offline transport tests; no runtime target override exists.
    JsonEvidenceHttp(HttpClient client, Duration timeout, String userAgent) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Redirects must be disabled");
        }
        this.userAgent = userAgent;
        this.client = client;
        this.timeout = timeout;
    }

    Response receive(SourceEndpoint endpoint) {
        // No public URL API: even package-local transport use is restricted to the approved pair.
        if (!SourceEndpoint.BANK_HOLIDAYS_URL.equals(endpoint.getUrl())
                && !SourceEndpoint.TFL_UNDERGROUND_URL.equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("Transport requires an approved canonical endpoint");
        }
        return send(URI.create(endpoint.getUrl()), Map.of());
    }

    /**
     * National Highways only: the fixed canonical resource plus a caller-built, credential-free query and
     * the provider's required request headers. Callers still cannot choose the scheme, host or path.
     */
    Response receive(SourceEndpoint endpoint, String query, Map<String, String> headers) {
        if (!SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL.equals(endpoint.getUrl())) {
            throw new IllegalArgumentException("Parameterised transport requires the canonical road closures endpoint");
        }
        if (query == null || !SAFE_QUERY.matcher(query).matches() || CREDENTIAL_PARAMETER.matcher(query).find()) {
            throw new IllegalArgumentException("Query must be bounded, unencoded and credential-free");
        }
        return send(URI.create(endpoint.getUrl() + "?" + query), headers);
    }

    private Response send(URI target, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(timeout).header("User-Agent", userAgent)
                .header("Accept", "application/json").header("Accept-Encoding", "identity");
        headers.forEach(builder::header);
        HttpRequest request = builder.GET().build();
        Instant requestedAt = Instant.now();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> {
            validateHeaders(info);
            return new LimitedBody();
        });
        try {
            // Covers headers AND the entire body, including a stalled/chunked body.
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.body().length == 0) {
                throw new AcquisitionFailure("EMPTY_BODY", "Response body was empty");
            }
            return new Response(response.body(), response.headers().firstValue("Content-Type").orElseThrow(),
                    requestedAt, Instant.now(), response.headers().allValues("x-next"));
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new AcquisitionFailure("TIMEOUT", "Response deadline exceeded");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AcquisitionFailure("INTERRUPTED", "Acquisition was interrupted");
        } catch (ExecutionException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof AcquisitionFailure failure) { throw failure; }
                if (cause instanceof java.net.http.HttpTimeoutException) {
                    throw new AcquisitionFailure("TIMEOUT", "HTTP request timed out");
                }
            }
            throw new AcquisitionFailure("NETWORK", "HTTP connection or response transfer failed");
        }
    }

    private void validateHeaders(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            throw new AcquisitionFailure("HTTP_STATUS", "Expected HTTP 200; received " + info.statusCode());
        }
        List<String> types = info.headers().allValues("Content-Type");
        if (types.size() != 1 || types.getFirst().length() > 200) {
            throw new AcquisitionFailure("CONTENT_TYPE", "Exactly one bounded application/json Content-Type is required");
        }
        try {
            MediaType type = MediaType.parseMediaType(types.getFirst());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) {
                throw new IllegalArgumentException("Unexpected media type");
            }
        } catch (IllegalArgumentException invalid) {
            throw new AcquisitionFailure("CONTENT_TYPE", "Response Content-Type must be application/json");
        }
        if (info.headers().allValues("Content-Encoding").stream().anyMatch(value -> !"identity".equalsIgnoreCase(value))) {
            throw new AcquisitionFailure("CONTENT_ENCODING", "Encoded responses are not accepted");
        }
        try {
            if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BODY_BYTES) {
                throw new AcquisitionFailure("RESPONSE_TOO_LARGE", "Response exceeds 1 MiB");
            }
        } catch (NumberFormatException invalid) {
            throw new AcquisitionFailure("RESPONSE_HEADERS", "Invalid response length");
        }
    }

    /** {@code next} holds every x-next continuation header value, uninterpreted. */
    record Response(byte[] bytes, String mediaType, Instant requestedAt, Instant observedAt, List<String> next) { }

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
                    body.completeExceptionally(new AcquisitionFailure("RESPONSE_TOO_LARGE", "Response exceeds 1 MiB"));
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
