package info.lifeinuk.backend.places;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static info.lifeinuk.backend.places.PlaceSearchFailure.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Real JDK client; requests rerouted to loopback only after asserting the canonical HTTPS target. Offline. */
class OsNamesClientTest {
    static final String FIXTURE_KEY = "offline-fixture-os-names-key-91aa";
    static final byte[] EMPTY = "{\"header\":{\"totalresults\":0}}".getBytes(StandardCharsets.UTF_8);
    private HttpServer server;
    private ExecutorService executor;
    private LocalClient local;
    private Handler handler;

    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws Exception; }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        handler = exchange -> respond(exchange, 200, "application/json", EMPTY);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        local = new LocalClient(server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        if (type != null) { exchange.getResponseHeaders().add("Content-Type", type); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
    }

    private OsNamesClient client(String key) {
        return new OsNamesClient(local, Duration.ofMillis(500), new OsNamesProperties(OsNamesProperties.CANONICAL_BASE_URL, key));
    }

    private PlaceSearchFailure.Reason failure(Runnable call) {
        var thrown = catchThrowableOfType(PlaceSearchFailure.class, call::run);
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain(FIXTURE_KEY);
        return thrown.reason();
    }

    @Test
    void requestUsesTheCanonicalFindEndpointWithEncodedQueryBoundedResultsPlaceFilterAndHeaderOnlyKey() {
        assertThat(client(FIXTURE_KEY).find("St Albans & Harpenden+")).containsExactly(EMPTY);
        assertThat(local.requests).hasSize(1);
        var request = local.requests.getFirst();
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo("api.os.uk");
        assertThat(request.uri().getRawPath()).isEqualTo("/search/names/v1/find");
        String raw = request.uri().getRawQuery();
        assertThat(raw).doesNotContain(FIXTURE_KEY, "key=");
        var parameters = new java.util.LinkedHashMap<String, String>();
        for (String pair : raw.split("&")) {
            int at = pair.indexOf('=');
            parameters.put(pair.substring(0, at), URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        assertThat(parameters).containsExactly(
                java.util.Map.entry("query", "St Albans & Harpenden+"),
                java.util.Map.entry("maxresults", "10"),
                java.util.Map.entry("fq", "LOCAL_TYPE:Postcode LOCAL_TYPE:City LOCAL_TYPE:Town LOCAL_TYPE:Village "
                        + "LOCAL_TYPE:Hamlet LOCAL_TYPE:Suburban_Area LOCAL_TYPE:Other_Settlement"),
                java.util.Map.entry("format", "JSON"));
        assertThat(request.headers().allValues("key")).containsExactly(FIXTURE_KEY);
        assertThat(request.headers().allValues("Accept")).containsExactly("application/json");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.timeout()).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "two words", "line\r\nInjected: x"})
    void missingOrHeaderUnsafeKeyIsNotConfiguredAndMakesNoRequest(String key) {
        assertThat(failure(() -> client(key).find("Leeds"))).isEqualTo(NOT_CONFIGURED);
        assertThat(local.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 403, 429, 500, 503})
    void non200IsUnavailableAndRedirectsAreNotFollowed(int status) {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.invalid/elsewhere");
            respond(exchange, status, "application/json", ("{\"error\":\"" + FIXTURE_KEY + "\"}").getBytes(StandardCharsets.UTF_8));
        };
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(UNAVAILABLE);
        assertThat(local.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "text/html", "application/xml", "not a type"})
    void nonJsonResponsesAreInvalid(String type) {
        handler = exchange -> respond(exchange, 200, type.equals("missing") ? null : type, EMPTY);
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(INVALID_RESPONSE);
    }

    @Test
    void oversizedBodyIsInvalid() {
        handler = exchange -> respond(exchange, 200, "application/json", new byte[OsNamesClient.MAX_BODY_BYTES + 1]);
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(INVALID_RESPONSE);
    }

    @Test
    void stalledResponseTimesOutAsUnavailable() {
        handler = exchange -> Thread.sleep(1500);
        long started = System.nanoTime();
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void connectionFailureAndUnexpectedClientFailureAreUnavailableWithoutLeakingDetails() {
        server.stop(0);
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(UNAVAILABLE);
        local.failure = new IllegalStateException("PRIVATE " + FIXTURE_KEY);
        assertThat(failure(() -> client(FIXTURE_KEY).find("Leeds"))).isEqualTo(UNAVAILABLE);
    }

    @Test
    void redirectFollowingClientsAndNonCanonicalBaseUrlsAreRefused() {
        HttpClient following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        assertThatIllegalArgumentException().isThrownBy(() -> new OsNamesClient(following, Duration.ofSeconds(1),
                new OsNamesProperties(OsNamesProperties.CANONICAL_BASE_URL, FIXTURE_KEY)));
        for (String base : List.of("http://api.os.uk/search/names/v1", "https://example.invalid/search/names/v1",
                "https://api.os.uk/search/names/v2", "https://api.os.uk/search/names/v1?key=x", "https://api.os.uk/search/places/v1")) {
            assertThatIllegalArgumentException().isThrownBy(() -> new OsNamesProperties(base, FIXTURE_KEY));
        }
        assertThat(new OsNamesProperties(OsNamesProperties.CANONICAL_BASE_URL + "/", FIXTURE_KEY).baseUrl())
                .isEqualTo(OsNamesProperties.CANONICAL_BASE_URL);
        assertThat(new OsNamesProperties(OsNamesProperties.CANONICAL_BASE_URL, FIXTURE_KEY).toString())
                .doesNotContain(FIXTURE_KEY).contains("<redacted>");
    }

    record Recorded(URI uri, String method, HttpHeaders headers, Optional<Duration> timeout) { }

    /** Reroutes to loopback after asserting the production target; real JDK transport for everything else. */
    static final class LocalClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300))
                .followRedirects(Redirect.NEVER).build();
        final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        private final int port;
        volatile RuntimeException failure;
        LocalClient(int port) { this.port = port; }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(new Recorded(request.uri(), request.method(), request.headers(), request.timeout()));
            assertThat(request.uri().toString()).startsWith(OsNamesProperties.CANONICAL_BASE_URL + "/find?");
            if (failure != null) { throw failure; }
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + request.uri().getRawPath()
                    + "?" + request.uri().getRawQuery())).GET().timeout(request.timeout().orElseThrow());
            request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            return delegate.sendAsync(builder.build(), handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
    }
}
