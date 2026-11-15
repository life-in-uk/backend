package info.lifeinuk.backend.acquisition;

import static info.lifeinuk.backend.underground.UndergroundCurrentStateProjector.Outcome.APPLIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.source.TflUndergroundBootstrap;
import info.lifeinuk.backend.support.IsolatedPostgres;
import info.lifeinuk.backend.underground.UndergroundCurrentState;
import info.lifeinuk.backend.underground.UndergroundCurrentStates;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/** Latest-only refresh: replaces the one Current State snapshot and never writes run or evidence history. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=tfl_latest_refresh_tests", "spring.flyway.default-schema=tfl_latest_refresh_tests",
    "spring.jpa.properties.hibernate.default_schema=tfl_latest_refresh_tests",
    "spring.datasource.hikari.schema=tfl_latest_refresh_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class TflUndergroundLatestRefreshTest {
    // The eleven Tube lines TfL lists for /Line/Mode/tube/Status (authored offline fixture).
    static final List<String[]> LINES = List.of(
            new String[] {"bakerloo", "Bakerloo"}, new String[] {"central", "Central"}, new String[] {"circle", "Circle"},
            new String[] {"district", "District"}, new String[] {"hammersmith-city", "Hammersmith & City"},
            new String[] {"jubilee", "Jubilee"}, new String[] {"metropolitan", "Metropolitan"},
            new String[] {"northern", "Northern"}, new String[] {"piccadilly", "Piccadilly"},
            new String[] {"victoria", "Victoria"}, new String[] {"waterloo-city", "Waterloo & City"});

    @Autowired TflUndergroundLatestRefresh refresh;
    @Autowired UndergroundCurrentStates states;
    @Autowired JdbcTemplate jdbc;
    @Autowired TflUndergroundBootstrap bootstrap;
    @Autowired RedirectingClient client;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status;
    private volatile byte[] body;
    private volatile long delayMillis;

    @TestConfiguration
    static class Configuration {
        @Bean RedirectingClient redirectingClient() { return new RedirectingClient(); }
        @Bean @Primary TflUndergroundHttp offlineHttp(RedirectingClient client) {
            return new TflUndergroundHttp(client, Duration.ofMillis(500));
        }
    }

    /** All eleven lines; {@code disrupted} gets Severe Delays, the rest Good Service. */
    static byte[] tube(String disrupted) {
        var json = new StringBuilder("[");
        for (var line : LINES) {
            boolean bad = line[0].equals(disrupted);
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":\"").append(line[0]).append("\",\"name\":\"").append(line[1])
                    .append("\",\"modeName\":\"tube\",\"lineStatuses\":[{\"statusSeverity\":").append(bad ? 6 : 10)
                    .append(",\"statusSeverityDescription\":\"").append(bad ? "Severe Delays" : "Good Service")
                    .append("\",\"reason\":").append(bad ? "\"Signal failure.\"" : "null").append("}]}");
        }
        return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void prepare() throws IOException {
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, underground_current_status, underground_current_line, underground_current_snapshot");
        bootstrap.initialize();
        jdbc.update("UPDATE source SET enabled = true WHERE source_key = 'transport-for-london'");
        jdbc.update("UPDATE source_endpoint SET enabled = true, qualification_status = 'QUALIFIED', qualification_record = 'Fixture owner approval', use_retention_policy = ? WHERE endpoint_key = 'tfl-underground-status'",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        status = 200;
        body = tube("victoria");
        delayMillis = 0;
        requests.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException | IOException ignored) {
                // Client gave up (timeout test).
            } finally {
                exchange.close();
            }
        });
        server.start();
        client.target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/Line/Mode/tube/Status");
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, underground_current_status, underground_current_line, underground_current_snapshot");
        jdbc.update("UPDATE source_endpoint SET qualification_status = 'PENDING', qualification_record = NULL, enabled = true WHERE endpoint_key = 'tfl-underground-status'");
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void assertNoHistory() {
        assertThat(count("ingestion_run")).isZero();
        assertThat(count("evidence_artifact")).isZero();
    }

    @Test
    void successStoresAllElevenLinesAsTheOneSnapshotWithoutHistory() {
        var result = refresh.refresh();
        assertThat(result.outcome()).isEqualTo(APPLIED);
        UndergroundCurrentState state = states.current().orElseThrow();
        assertThat(state.lines()).extracting(line -> line.lineId())
                .containsExactlyElementsOf(LINES.stream().map(line -> line[0]).toList());
        assertThat(state.lines().get(9).statuses().getFirst().description()).isEqualTo("Severe Delays");
        assertThat(count("underground_current_snapshot")).isEqualTo(1);
        assertThat(count("underground_current_line")).isEqualTo(11);
        assertThat(count("underground_current_status")).isEqualTo(11);
        assertNoHistory();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void repeatedRefreshesReplaceTheSnapshotAndGrowNoTable() {
        refresh.refresh();
        var first = states.current().orElseThrow();
        body = tube("central");
        for (int i = 0; i < 3; i++) {
            assertThat(refresh.refresh().outcome()).isEqualTo(APPLIED);
        }
        var latest = states.current().orElseThrow();
        assertThat(latest.observedAt()).isAfter(first.observedAt());
        assertThat(latest.evidenceArtifactId()).isNotEqualTo(first.evidenceArtifactId());
        assertThat(latest.lines().get(1).statuses().getFirst().description()).isEqualTo("Severe Delays");
        assertThat(latest.lines().get(9).statuses().getFirst().description()).isEqualTo("Good Service");
        assertThat(count("underground_current_snapshot")).isEqualTo(1);
        assertThat(count("underground_current_line")).isEqualTo(11);
        assertThat(count("underground_current_status")).isEqualTo(11);
        assertNoHistory();
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void failuresKeepThePreviousSnapshotAndItsObservationTime() {
        refresh.refresh();
        var before = states.current().orElseThrow();
        status = 503;
        assertFailure("HTTP_STATUS");
        status = 429;
        var limited = refresh.refresh();
        assertThat(limited.rateLimited()).isTrue();
        status = 200;
        body = "{not json".getBytes(StandardCharsets.UTF_8);
        assertFailure("INVALID_RESPONSE");
        body = "[]".getBytes(StandardCharsets.UTF_8);
        assertFailure("INCOMPLETE_RESPONSE");
        body = "[{\"id\":\"victoria\",\"name\":\"Victoria\",\"modeName\":\"bus\",\"lineStatuses\":[]}]".getBytes(StandardCharsets.UTF_8);
        assertFailure("INVALID_RESPONSE");
        body = new byte[0];
        assertThat(refresh.refresh().failureCode()).isNotNull();
        body = tube("central");
        delayMillis = 1500;
        assertThat(refresh.refresh().failureCode()).isNotNull();
        delayMillis = 0;
        var after = states.current().orElseThrow();
        assertThat(after.observedAt()).isEqualTo(before.observedAt());
        assertThat(after.evidenceArtifactId()).isEqualTo(before.evidenceArtifactId());
        assertThat(after.lines()).isEqualTo(before.lines());
        assertNoHistory();
    }

    private void assertFailure(String code) {
        var result = refresh.refresh();
        assertThat(result.outcome()).isNull();
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(result.failureMessage()).doesNotContain("Signal failure", "{not json", "Exception");
    }

    @Test
    void anIneligibleEndpointMakesNoRequestAndChangesNothing() {
        refresh.refresh();
        var before = states.current().orElseThrow();
        jdbc.update("UPDATE source_endpoint SET enabled = false WHERE endpoint_key = 'tfl-underground-status'");
        assertThatThrownBy(() -> refresh.refresh()).isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(states.current().orElseThrow()).isEqualTo(before);
        assertNoHistory();
    }

    /** Sends the canonical TfL request to the local fixture server instead of the internet. */
    static final class RedirectingClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300))
                .followRedirects(Redirect.NEVER).build();
        private volatile URI target;
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            assertThat(request.uri().toString()).isEqualTo(SourceEndpoint.TFL_UNDERGROUND_URL);
            HttpRequest.Builder local = HttpRequest.newBuilder(target).GET().timeout(request.timeout().orElseThrow());
            request.headers().map().forEach((name, values) -> values.forEach(value -> local.header(name, value)));
            return delegate.sendAsync(local.build(), handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
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
