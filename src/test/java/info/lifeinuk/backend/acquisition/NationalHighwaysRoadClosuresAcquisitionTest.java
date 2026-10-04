package info.lifeinuk.backend.acquisition;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.source.NationalHighwaysBootstrap;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Entirely offline: the real JDK client is rerouted to a loopback server after canonical-target assertions. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=nh_acquisition_tests", "spring.flyway.default-schema=nh_acquisition_tests",
    "spring.jpa.properties.hibernate.default_schema=nh_acquisition_tests", "spring.datasource.hikari.schema=nh_acquisition_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@ExtendWith(OutputCaptureExtension.class)
class NationalHighwaysRoadClosuresAcquisitionTest {
    /** Fixture only: never a real National Highways subscription key. */
    static final String FIXTURE_KEY = "offline-fixture-subscription-key-5d1e";
    /** 12:19:43 BST: UTC formatting must yield 11:19:43, proving no local-time conversion. */
    static final Instant NOW = Instant.parse("2026-10-04T11:19:43.987654321Z");
    static final String WINDOW = "closureType=unplanned&startDateTime=2026-10-04T05:19:43&endDateTime=2026-10-04T11:19:43";
    static final String CANONICAL = SourceEndpoint.NATIONAL_HIGHWAYS_ROAD_CLOSURES_URL;

    @Autowired NationalHighwaysRoadClosuresAcquisition acquisition;
    @Autowired LocalClient client;
    @Autowired SimulatedTime time;
    @Autowired JdbcTemplate jdbc;
    @Autowired NationalHighwaysBootstrap bootstrap;
    @Autowired PlatformTransactionManager transactions;
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired AcquisitionHistory history;
    private byte[] raw;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private Handler handler;

    @TestConfiguration
    static class Configuration {
        @Bean LocalClient localClient() { return new LocalClient(); }
        @Bean SimulatedTime simulatedTime() { return new SimulatedTime(); }
        @Bean @Primary NationalHighwaysRoadClosuresAcquisition offlineAcquisition(QualifiedSourceEndpoints endpoints,
                AcquisitionHistory history, LocalClient client, SimulatedTime time) {
            var http = new NationalHighwaysHttp(client, Duration.ofMillis(500),
                    new NationalHighwaysRoadsProperties(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL, FIXTURE_KEY));
            return new NationalHighwaysRoadClosuresAcquisition(endpoints, history, http, Clock.fixed(NOW, ZoneOffset.UTC),
                    new RequestPacer(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING, time::nanos, time::sleep));
        }
    }

    /** Simulated monotonic time: advances only when the pacer sleeps, and records every sleep. */
    static final class SimulatedTime {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        long nanos() { return nanos.get(); }
        void sleep(Duration duration) { sleeps.add(duration); nanos.addAndGet(duration.toNanos()); }
    }

    @BeforeEach
    void prepare() throws IOException {
        try (var input = getClass().getResourceAsStream("/nationalhighways/unplanned-page.json")) {
            raw = input.readAllBytes();
        }
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='national-highways') WHERE endpoint_key='national-highways-road-closures'");
        bootstrap.initialize();
        jdbc.update("UPDATE source SET enabled=true WHERE source_key='national-highways'");
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='national-highways-road-closures'",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        handler = exchange -> respond(exchange, 200, "application/json; charset=utf-8", raw, null);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            catch (Throwable failure) { serverFailure.set(failure); }
            finally { exchange.close(); }
        });
        server.start();
        client.port = server.getAddress().getPort();
        client.requests.clear();
        client.failure = null;
        // The paced bean is shared by every test: let simulated time pass so each test starts unthrottled.
        time.nanos.addAndGet(Duration.ofHours(1).toNanos());
        time.sleeps.clear();
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS nh_test_reject ON evidence_artifact");
        jdbc.execute("DROP FUNCTION IF EXISTS nh_test_reject()");
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='national-highways') WHERE endpoint_key='national-highways-road-closures'");
        jdbc.update("UPDATE source SET enabled=true WHERE source_key='national-highways'");
        jdbc.update("UPDATE source_endpoint SET qualification_status='PENDING', qualification_record=NULL, enabled=true WHERE endpoint_key='national-highways-road-closures'");
    }

    private void respond(HttpExchange exchange, int status, String type, byte[] body, String next) throws IOException {
        if (type != null) { exchange.getResponseHeaders().add("Content-Type", type); }
        if (next != null) { exchange.getResponseHeaders().add("x-next", next); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
    }

    private static String nextLink(String cursor) {
        return CANONICAL + "?closureType=unplanned&PageCursor=" + cursor;
    }
    private static byte[] page(int number) {
        return ("{\"D2Payload\":{\"page\":" + number + ",\"note\":\"offline — Café\"}}").getBytes(StandardCharsets.UTF_8);
    }
    private static String cursorOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        int at = query.indexOf("&pageCursor=");
        return at < 0 ? null : query.substring(at + "&pageCursor=".length());
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private String status(UUID run) { return jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id=?", String.class, run); }
    private String failureCode(UUID run) { return jdbc.queryForObject("SELECT failure_code FROM ingestion_run WHERE id=?", String.class, run); }

    private void failedWithoutEvidence(UUID run, String code, int requests) {
        assertThat(status(run)).isEqualTo("FAILED");
        assertThat(failureCode(run)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT length(failure_message) FROM ingestion_run WHERE id=?", Integer.class, run)).isBetween(1, 1000);
        assertThat(count("evidence_artifact")).isZero();
        assertThat(client.requests).hasSize(requests);
    }

    private String everyRowInSchema() {
        var all = new StringBuilder();
        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema()", String.class)) {
            jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t", String.class).forEach(all::append);
        }
        return all.toString();
    }

    @Test
    void singlePageRunRequestsExplicitUnplannedWindowWithHeaderOnlyKeyAndPersistsExactEvidence() throws Exception {
        UUID run = acquisition.acquire();
        assertThat(serverFailure.get()).isNull();
        assertThat(status(run)).isEqualTo("SUCCESS");
        assertThat(client.requests).hasSize(1);
        Recorded request = client.requests.getFirst();
        assertThat(request.uri()).isEqualTo(URI.create(CANONICAL + "?" + WINDOW));
        assertThat(request.uri().getRawQuery()).doesNotContain("pageCursor", "modifiedSinceDateTime", "closureType=planned", FIXTURE_KEY)
                .doesNotContainIgnoringCase("subscription");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.transactionActive()).isFalse();
        assertThat(request.headers().allValues("Ocp-Apim-Subscription-Key")).containsExactly(FIXTURE_KEY);
        assertThat(request.headers().allValues("X-Response-MediaType")).containsExactly("application/json");
        assertThat(request.headers().allValues("X-Data-Format")).containsExactly("DATEXII");
        assertThat(request.headers().allValues("Accept")).containsExactly("application/json");
        assertThat(request.headers().allValues("Accept-Encoding")).containsExactly("identity");
        assertThat(request.headers().allValues("User-Agent")).containsExactly(NationalHighwaysHttp.USER_AGENT);

        var row = jdbc.queryForMap("SELECT * FROM evidence_artifact WHERE ingestion_run_id=?", run);
        assertThat((byte[]) row.get("payload")).containsExactly(raw);
        assertThat(row.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        assertThat(row.get("media_type")).isEqualTo("application/json; charset=utf-8");
        assertThat(row.get("request_query")).isEqualTo(WINDOW);
        assertThat(row.get("page_number")).isEqualTo(1);
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM evidence_artifact a JOIN ingestion_run r ON r.id=a.ingestion_run_id
                JOIN source_endpoint e ON e.id=r.source_endpoint_id JOIN source s ON s.id=e.source_id
                WHERE r.id=? AND e.endpoint_key='national-highways-road-closures' AND s.source_key='national-highways'
                  AND a.requested_at >= r.started_at AND a.requested_at <= a.observed_at AND a.observed_at <= r.completed_at
                """, Integer.class, run)).isEqualTo(1);
    }

    @Test
    void providerContinuationIsFollowedSequentiallyIntoOneRunOwningEveryPageExactly() throws Exception {
        handler = exchange -> {
            String cursor = cursorOf(exchange);
            assertThat(exchange.getRequestURI().getRawQuery()).startsWith(WINDOW);
            switch (cursor == null ? "first" : cursor) {
                case "first" -> respond(exchange, 200, "application/json", page(1), nextLink("4021"));
                case "4021" -> respond(exchange, 200, "application/json", page(2),
                        CANONICAL + "?closureType=unplanned&startDateTime=x&pageCursor=4022");
                case "4022" -> respond(exchange, 200, "application/json", page(3), null);
                default -> respond(exchange, 500, "application/json", new byte[] {'{', '}'}, null);
            }
        };
        UUID run = acquisition.acquire();
        assertThat(serverFailure.get()).isNull();
        assertThat(status(run)).isEqualTo("SUCCESS");
        assertThat(client.requests).extracting(r -> r.uri().getRawQuery())
                .containsExactly(WINDOW, WINDOW + "&pageCursor=4021", WINDOW + "&pageCursor=4022");
        var rows = jdbc.queryForList("SELECT page_number, request_query, payload, sha256 FROM evidence_artifact WHERE ingestion_run_id=? ORDER BY page_number", run);
        assertThat(rows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            var row = rows.get(i);
            assertThat(row.get("page_number")).isEqualTo(i + 1);
            assertThat(row.get("request_query")).isEqualTo(client.requests.get(i).uri().getRawQuery());
            assertThat((byte[]) row.get("payload")).containsExactly(page(i + 1));
            assertThat(row.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(page(i + 1))));
        }
        // Pages are paced, never parallel: each follow-up waited for the full minimum spacing.
        assertThat(time.sleeps).containsExactly(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING,
                NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING);
    }

    @Test
    void failureDuringPaginationFailsTheWholeRunAndPersistsNoPartialEvidence() {
        handler = exchange -> {
            if (cursorOf(exchange) == null) { respond(exchange, 200, "application/json", page(1), nextLink("7")); }
            else { respond(exchange, 500, "application/json", "{\"error\":\"PRIVATE_BODY_MARKER\"}".getBytes(StandardCharsets.UTF_8), null); }
        };
        UUID run = acquisition.acquire();
        failedWithoutEvidence(run, "HTTP_STATUS", 2);
        assertThat(jdbc.queryForObject("SELECT failure_message FROM ingestion_run WHERE id=?", String.class, run))
                .isEqualTo("Unplanned page 2: Expected HTTP 200; received 500").doesNotContain("PRIVATE_BODY_MARKER");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 422, 429, 500})
    void non200FirstPageFailsWithoutFollowingRedirectsOrRetrying(int code) {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Location", CANONICAL + "?closureType=unplanned&pageCursor=9");
            respond(exchange, code, "application/json", "{}".getBytes(StandardCharsets.UTF_8), nextLink("9"));
        };
        failedWithoutEvidence(acquisition.acquire(), "HTTP_STATUS", 1);
    }

    @Test
    void repeatedProviderCursorIsALoopNotAnInfiniteRun() {
        handler = exchange -> respond(exchange, 200, "application/json", page(1), nextLink("55"));
        failedWithoutEvidence(acquisition.acquire(), "PAGINATION_LOOP", 2);
    }

    @Test
    void endlessDistinctContinuationStopsAtTheBoundWithoutAnExtraRequest() {
        AtomicLong next = new AtomicLong();
        handler = exchange -> respond(exchange, 200, "application/json", page(1), nextLink(Long.toString(next.incrementAndGet())));
        UUID run = acquisition.acquire();
        failedWithoutEvidence(run, "PAGINATION_LIMIT", NationalHighwaysRoadClosuresAcquisition.MAX_PAGES);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=2",
        "https://example.invalid/roads/v2.0/closures?pageCursor=2",
        "https://api.data.nationalhighways.co.uk/roads/v1.0/closures?pageCursor=2",
        "https://api.data.nationalhighways.co.uk:8443/roads/v2.0/closures?pageCursor=2",
        "https://user@api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=2",
        "https://api.data.nationalhighways.co.uk/roads/v2.0/closures?closureType=unplanned",
        "https://api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=2&PageCursor=3",
        "https://api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=a%20b",
        "https://api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=",
        "https://api.data.nationalhighways.co.uk/roads/v2.0/closures?pageCursor=2#fragment",
        "not a uri"
    })
    void untrustworthyContinuationFailsBeforeAnyFurtherRequest(String next) {
        handler = exchange -> respond(exchange, 200, "application/json", page(1), next);
        failedWithoutEvidence(acquisition.acquire(), "PAGINATION_INVALID", 1);
    }

    @Test
    void multipleContinuationHeadersAreAmbiguous() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("x-next", nextLink("2"));
            respond(exchange, 200, "application/json", page(1), nextLink("3"));
        };
        failedWithoutEvidence(acquisition.acquire(), "PAGINATION_INVALID", 1);
    }

    @Test
    void blankContinuationMeansNoFurtherPage() {
        handler = exchange -> respond(exchange, 200, "application/json", page(1), "");
        UUID run = acquisition.acquire();
        assertThat(status(run)).isEqualTo("SUCCESS");
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void oversizedPageFailsTheRun() {
        handler = exchange -> respond(exchange, 200, "application/json", new byte[JsonEvidenceHttp.MAX_BODY_BYTES + 1], null);
        failedWithoutEvidence(acquisition.acquire(), "RESPONSE_TOO_LARGE", 1);
    }

    @Test
    void stalledPageTimesOutWithinTheBound() {
        handler = exchange -> Thread.sleep(1500);
        long start = System.nanoTime();
        failedWithoutEvidence(acquisition.acquire(), "TIMEOUT", 1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void connectionFailureIsAFailedRun() {
        server.stop(0);
        failedWithoutEvidence(acquisition.acquire(), "NETWORK", 1);
    }

    @Test
    void unexpectedTransportFailureIsBoundedAndDoesNotLeakDiagnostics() {
        client.failure = new IllegalStateException("PRIVATE_DIAGNOSTIC_MARKER " + FIXTURE_KEY);
        UUID run = acquisition.acquire();
        failedWithoutEvidence(run, "TRANSPORT", 1);
        assertThat(jdbc.queryForObject("SELECT failure_message FROM ingestion_run WHERE id=?", String.class, run))
                .isEqualTo("Unplanned page 1: HTTP transport could not complete");
    }

    @Test
    void persistenceFailureRollsBackEveryPageAndRecordsFailure() {
        handler = exchange -> {
            if (cursorOf(exchange) == null) { respond(exchange, 200, "application/json", page(1), nextLink("2")); }
            else { respond(exchange, 200, "application/json", page(2), null); }
        };
        jdbc.execute("CREATE FUNCTION nh_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.page_number = 2 THEN RAISE EXCEPTION 'Injected page failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER nh_test_reject BEFORE INSERT ON evidence_artifact FOR EACH ROW EXECUTE FUNCTION nh_test_reject()");
        failedWithoutEvidence(acquisition.acquire(), "EVIDENCE_PERSISTENCE", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE source_endpoint SET qualification_status='PENDING', qualification_record=NULL WHERE endpoint_key='national-highways-road-closures'",
        "UPDATE source_endpoint SET enabled=false WHERE endpoint_key='national-highways-road-closures'",
        "UPDATE source SET enabled=false WHERE source_key='national-highways'",
        "UPDATE source_endpoint SET qualification_record=' ' WHERE endpoint_key='national-highways-road-closures'",
        "UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='transport-for-london') WHERE endpoint_key='national-highways-road-closures'",
        "DELETE FROM source_endpoint WHERE endpoint_key='national-highways-road-closures'"
    })
    void ineligibleConfigurationFailsBeforeAnyRunOrRequest(String change) {
        jdbc.update(change);
        assertThatThrownBy(acquisition::acquire).isInstanceOf(RuntimeException.class);
        assertThat(count("ingestion_run")).isZero();
        assertThat(client.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "key with space", "line\r\nInjected: header", "tab\tkey"})
    void missingOrHeaderUnsafeKeyFailsBeforeAnyRunOrRequestWithoutEchoingIt(String key) {
        var http = new NationalHighwaysHttp(client, Duration.ofMillis(500),
                new NationalHighwaysRoadsProperties(SourceEndpoint.NATIONAL_HIGHWAYS_ROADS_BASE_URL, key));
        var unusable = new NationalHighwaysRoadClosuresAcquisition(endpoints, history, http,
                Clock.fixed(NOW, ZoneOffset.UTC), new RequestPacer(Duration.ofSeconds(7), time::nanos, time::sleep));
        assertThatIllegalStateException().isThrownBy(unusable::acquire)
                .withMessage("A usable National Highways subscription key is not configured");
        assertThat(count("ingestion_run")).isZero();
        assertThat(client.requests).isEmpty();
    }

    @Test
    void applicationBoundaryTakesNoCallerInputAndRejectsAnAmbientTransaction() {
        assertThat(Arrays.stream(NationalHighwaysRoadClosuresAcquisition.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList())
                .allSatisfy(method -> assertThat(method.getParameterCount()).isZero());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> acquisition.acquire()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(client.requests).isEmpty();
        assertThat(count("ingestion_run")).isZero();
    }

    @Test
    void consecutiveRunsArePacedAcrossRunsAndEachIsIndependentHistory() {
        UUID first = acquisition.acquire();
        UUID second = acquisition.acquire();
        assertThat(first).isNotEqualTo(second);
        assertThat(status(first)).isEqualTo("SUCCESS");
        assertThat(status(second)).isEqualTo("SUCCESS");
        assertThat(count("evidence_artifact")).isEqualTo(2);
        assertThat(time.sleeps).containsExactly(NationalHighwaysRoadClosuresAcquisition.MIN_REQUEST_SPACING);
    }

    @Test
    void subscriptionKeyNeverReachesPersistenceOrLogs(CapturedOutput output) {
        acquisition.acquire();
        handler = exchange -> respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null);
        acquisition.acquire();
        client.failure = new IllegalStateException(FIXTURE_KEY);
        acquisition.acquire();
        assertThat(client.requests).allSatisfy(r -> assertThat(r.uri().toString()).doesNotContain(FIXTURE_KEY));
        assertThat(everyRowInSchema()).doesNotContain(FIXTURE_KEY).doesNotContainIgnoringCase("Ocp-Apim");
        assertThat(output.getAll()).doesNotContain(FIXTURE_KEY);
    }

    @Test
    void otherProvidersConfigurationAndHistoryAreUntouched() {
        String before = jdbc.queryForObject("""
                SELECT json_agg(row_to_json(e) ORDER BY e.endpoint_key)::text FROM source_endpoint e
                WHERE e.endpoint_key IN ('gov-uk-bank-holidays-json','tfl-underground-status')
                """, String.class);
        assertThat(status(acquisition.acquire())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("""
                SELECT json_agg(row_to_json(e) ORDER BY e.endpoint_key)::text FROM source_endpoint e
                WHERE e.endpoint_key IN ('gov-uk-bank-holidays-json','tfl-underground-status')
                """, String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ingestion_run r JOIN source_endpoint e ON e.id=r.source_endpoint_id
                WHERE e.endpoint_key <> 'national-highways-road-closures'
                """, Integer.class)).isZero();
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    record Recorded(URI uri, String method, HttpHeaders headers, boolean transactionActive) { }

    /** Test-only rerouting after asserting the production request targets the canonical HTTPS endpoint. */
    static final class LocalClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300))
                .followRedirects(Redirect.NEVER).build();
        final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
        volatile int port;
        volatile RuntimeException failure;
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(new Recorded(request.uri(), request.method(), request.headers(),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            assertThat(request.uri().getScheme()).isEqualTo("https");
            assertThat(request.uri().getHost()).isEqualTo("api.data.nationalhighways.co.uk");
            assertThat(request.uri().getRawPath()).isEqualTo("/roads/v2.0/closures");
            assertThat(request.timeout()).isPresent();
            if (failure != null) { throw failure; }
            HttpRequest.Builder local = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + request.uri().getRawPath() + "?" + request.uri().getRawQuery())).GET().timeout(request.timeout().orElseThrow());
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
