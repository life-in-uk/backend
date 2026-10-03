package info.lifeinuk.backend.acquisition;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.source.BankHolidaysBootstrap;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.schemas=acquisition_tests", "spring.flyway.default-schema=acquisition_tests",
    "spring.jpa.properties.hibernate.default_schema=acquisition_tests", "spring.datasource.hikari.schema=acquisition_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class BankHolidaysAcquisitionTest {
    private static final byte[] RAW = "{\r\n \"raw\": \"£\", \"events\": [] }\n".getBytes(StandardCharsets.UTF_8);
    @Autowired BankHolidaysAcquisition acquisition;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankHolidaysBootstrap bootstrap;
    @Autowired LocalClient client;
    @Autowired PlatformTransactionManager transactions;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private Handler handler;

    @TestConfiguration
    static class Configuration {
        @Bean LocalClient localClient() { return new LocalClient(); }
        @Bean @Primary BankHolidaysHttp offlineHttp(LocalClient client) {
            return new BankHolidaysHttp(client, Duration.ofMillis(500));
        }
    }

    @BeforeEach
    void prepare() throws IOException {
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        bootstrap.initialize();
        jdbc.update("UPDATE source SET enabled = true WHERE source_key = 'gov-uk-bank-holidays'");
        jdbc.update("UPDATE source_endpoint SET enabled = true, qualification_status = 'QUALIFIED', qualification_record = 'Fixture owner approval', use_retention_policy = 'Approved fixture retention' WHERE endpoint_key = 'gov-uk-bank-holidays-json'");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        handler = exchange -> respond(exchange, 200, "application/json; charset=utf-8", RAW, false);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); }
            catch (Throwable failure) { serverFailure.set(failure); }
            finally { exchange.close(); }
        });
        server.start();
        client.target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bank-holidays.json");
        client.original.set(null);
        client.transactionActive = false;
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS acquisition_test_reject ON evidence_artifact");
        jdbc.execute("DROP TRIGGER IF EXISTS acquisition_test_reject ON ingestion_run");
        jdbc.execute("DROP FUNCTION IF EXISTS acquisition_test_reject()");
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        bootstrap.initialize();
        jdbc.update("UPDATE source_endpoint SET qualification_status = 'PENDING', qualification_record = NULL, enabled = true WHERE endpoint_key = 'gov-uk-bank-holidays-json'");
    }

    private void respond(HttpExchange exchange, int status, String type, byte[] body, boolean chunked) throws IOException {
        if (type != null) { exchange.getResponseHeaders().add("Content-Type", type); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : chunked ? 0 : body.length);
        exchange.getResponseBody().write(body);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id = ?", String.class, id); }
    private void failed(String code) {
        UUID id = acquisition.acquire();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM ingestion_run WHERE id = ?", String.class, id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT length(failure_message) FROM ingestion_run WHERE id = ?", Integer.class, id)).isBetween(1, 1000);
        assertThat(count("evidence_artifact")).isZero();
    }

    @Test
    void successPreservesBytesMetadataHashAndCommittedStartedRunOutsideTransaction() throws Exception {
        handler = exchange -> {
            assertThat(count("ingestion_run")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run", String.class)).isEqualTo("STARTED");
            assertThat(count("evidence_artifact")).isZero();
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).isEqualTo(BankHolidaysHttp.USER_AGENT);
            assertThat(exchange.getRequestHeaders().getFirst("Accept-Encoding")).isEqualTo("identity");
            respond(exchange, 200, "application/json; charset=utf-8", RAW, false);
        };
        UUID id = acquisition.acquire();
        assertThat(serverFailure.get()).isNull();
        assertThat(state(id)).isEqualTo("SUCCESS");
        assertThat(client.transactionActive).isFalse();
        assertThat(client.original.get().uri().toString()).isEqualTo(jdbc.queryForObject("SELECT url FROM source_endpoint WHERE endpoint_key='gov-uk-bank-holidays-json'", String.class));
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, id)).containsExactly(RAW);
        assertThat(jdbc.queryForObject("SELECT media_type FROM evidence_artifact", String.class)).isEqualTo("application/json; charset=utf-8");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM evidence_artifact", String.class)).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RAW)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact a JOIN ingestion_run r ON r.id = a.ingestion_run_id JOIN source_endpoint e ON e.id = r.source_endpoint_id JOIN source s ON s.id = e.source_id WHERE r.id = ? AND a.observed_at BETWEEN r.started_at AND r.completed_at", Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE source_endpoint SET qualification_status = 'PENDING', qualification_record = NULL WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
        "UPDATE source_endpoint SET enabled = false WHERE endpoint_key = 'gov-uk-bank-holidays-json'",
        "UPDATE source SET enabled = false WHERE source_key = 'gov-uk-bank-holidays'",
        "DELETE FROM source_endpoint"
    })
    void ineligibleConfigurationFailsBeforeRunAndNetwork(String change) {
        jdbc.update(change);
        assertThatThrownBy(acquisition::acquire).isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isZero();
        assertThat(count("ingestion_run")).isZero();
        assertThat(client.original.get()).isNull();
    }

    @Test
    void applicationBoundaryHasNoTargetArgumentAndRejectsAmbientTransaction() {
        assertThat(Arrays.stream(BankHolidaysAcquisition.class.getDeclaredMethods()).filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList())
                .allSatisfy(method -> assertThat(method.getParameterCount()).isZero());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> acquisition.acquire()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(requests.get()).isZero();
        assertThat(count("ingestion_run")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 304, 404, 500})
    void non200ResponsesFail(int status) {
        handler = exchange -> respond(exchange, status, "application/json", RAW, false);
        failed("HTTP_STATUS");
    }

    @Test
    void redirectsAreNotFollowed() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Location", client.target.resolve("/redirected").toString());
            respond(exchange, 302, "application/json", RAW, false);
        };
        failed("HTTP_STATUS");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "text/html", "not a media type", "application/problem+json"})
    void contentTypeContractRejectsUnexpectedOrMissingType(String type) {
        handler = exchange -> respond(exchange, 200, type.equals("missing") ? null : type, RAW, false);
        failed("CONTENT_TYPE");
    }

    @Test
    void duplicateContentTypesFail() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, "application/json", RAW, false);
        };
        failed("CONTENT_TYPE");
    }

    @Test
    void overlongContentTypeFails() {
        handler = exchange -> respond(exchange, 200, "application/json; note=" + "x".repeat(200), RAW, false);
        failed("CONTENT_TYPE");
    }

    @Test
    void encodedBodyIsRejectedRatherThanDecompressed() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            respond(exchange, 200, "application/json", RAW, false);
        };
        failed("CONTENT_ENCODING");
    }

    @Test
    void emptyBodyFails() {
        handler = exchange -> respond(exchange, 200, "application/json", new byte[0], false);
        failed("EMPTY_BODY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownLengthAndChunkedOversizedBodiesFail(boolean chunked) {
        handler = exchange -> respond(exchange, 200, "application/json", new byte[BankHolidaysHttp.MAX_BODY_BYTES + 1], chunked);
        failed("RESPONSE_TOO_LARGE");
    }

    @Test
    void bodyAtLimitIsPreserved() {
        byte[] body = new byte[BankHolidaysHttp.MAX_BODY_BYTES];
        Arrays.fill(body, (byte) ' ');
        handler = exchange -> respond(exchange, 200, "application/json", body, true);
        assertThat(state(acquisition.acquire())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact", byte[].class)).containsExactly(body);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutCoversHeadersAndStalledBody(boolean bodyStall) {
        handler = exchange -> {
            if (bodyStall) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            Thread.sleep(1500);
            if (!bodyStall) { respond(exchange, 200, "application/json", RAW, false); }
        };
        long start = System.nanoTime();
        failed("TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void connectionFailureIsHistoricalFailure() {
        server.stop(0);
        failed("NETWORK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidence_artifact", "ingestion_run"})
    void persistenceFailureRollsBackEvidenceAndSuccessBeforeRecordingFailure(String table) {
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF TG_TABLE_NAME = 'evidence_artifact' THEN RAISE EXCEPTION 'Injected artifact failure'; END IF; IF NEW.status = 'SUCCESS' THEN RAISE EXCEPTION 'Injected success failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE " + (table.equals("evidence_artifact") ? "INSERT" : "UPDATE") + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        failed("EVIDENCE_PERSISTENCE");
    }

    @Test
    void successTransitionSeesAlreadyPersistedArtifact() {
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'SUCCESS' AND NOT EXISTS (SELECT 1 FROM evidence_artifact WHERE ingestion_run_id = NEW.id) THEN RAISE EXCEPTION 'Success before evidence'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE UPDATE ON ingestion_run FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        assertThat(state(acquisition.acquire())).isEqualTo("SUCCESS");
        assertThat(count("evidence_artifact")).isEqualTo(1);
    }

    @Test
    void failurePersistenceUnavailabilityPropagatesAndLeavesStartedHistory() {
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'FAILED' THEN RAISE EXCEPTION 'Injected failure recording outage'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE UPDATE ON ingestion_run FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        handler = exchange -> respond(exchange, 500, "application/json", RAW, false);
        assertThatThrownBy(acquisition::acquire).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run", String.class)).isEqualTo("STARTED");
        assertThat(count("evidence_artifact")).isZero();
    }

    @Test
    void repeatedAndChangedResponsesCreateIndependentHistory() {
        UUID first = acquisition.acquire();
        UUID second = acquisition.acquire();
        handler = exchange -> respond(exchange, 200, "application/json", "{\"later\":true}".getBytes(StandardCharsets.UTF_8), false);
        UUID third = acquisition.acquire();
        assertThat(first).isNotEqualTo(second).isNotEqualTo(third);
        assertThat(count("ingestion_run")).isEqualTo(3);
        assertThat(count("evidence_artifact")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, first)).containsExactly(RAW);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, second)).containsExactly(RAW);
        assertThat(state(first)).isEqualTo("SUCCESS");
        assertThat(state(second)).isEqualTo("SUCCESS");
        assertThat(state(third)).isEqualTo("SUCCESS");
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    /** Test-only request rerouting: production still resolves the real persisted endpoint;
     * all HTTP/status/body/timeout behaviour uses the real JDK client and loopback server. */
    static final class LocalClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300))
                .followRedirects(Redirect.NEVER).build();
        private final AtomicReference<HttpRequest> original = new AtomicReference<>();
        private URI target;
        private boolean transactionActive;
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            original.set(request);
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            assertThat(request.uri().toString()).isEqualTo(SourceEndpoint.BANK_HOLIDAYS_URL);
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
