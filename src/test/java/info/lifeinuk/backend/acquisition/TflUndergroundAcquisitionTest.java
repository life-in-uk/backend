package info.lifeinuk.backend.acquisition;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import info.lifeinuk.backend.source.TflUndergroundBootstrap;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.evidence.AcquisitionHistory;
import info.lifeinuk.backend.evidence.EvidenceArtifact;
import jakarta.persistence.EntityManager;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import info.lifeinuk.backend.underground.UndergroundCurrentStates;
import info.lifeinuk.backend.underground.UndergroundEvidenceProjection;
import info.lifeinuk.backend.underground.UndergroundLineStatus;
import info.lifeinuk.backend.underground.UndergroundOperationalStatus;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static info.lifeinuk.backend.underground.UndergroundCurrentStateProjector.Outcome.APPLIED;
import static info.lifeinuk.backend.underground.UndergroundEvidenceProjection.Result.Failure.EVIDENCE_INVALID;
import static info.lifeinuk.backend.underground.UndergroundEvidenceProjection.Result.Failure.PROJECTION_FAILED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
    "spring.flyway.schemas=tfl_acquisition_tests", "spring.flyway.default-schema=tfl_acquisition_tests",
    "spring.jpa.properties.hibernate.default_schema=tfl_acquisition_tests", "spring.datasource.hikari.schema=tfl_acquisition_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class TflUndergroundAcquisitionTest {
    private byte[] raw;
    @Autowired TflUndergroundAcquisition acquisition;
    @Autowired JdbcTemplate jdbc;
    @Autowired TflUndergroundBootstrap bootstrap;
    @Autowired LocalClient client;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired AcquisitionHistory history;
    @Autowired UndergroundCurrentStates states;
    @MockitoSpyBean UndergroundEvidenceProjection projection;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private Handler handler;

    @TestConfiguration
    static class Configuration {
        @Bean LocalClient localClient() { return new LocalClient(); }
        @Bean @Primary TflUndergroundHttp offlineHttp(LocalClient client) {
            return new TflUndergroundHttp(client, Duration.ofMillis(500));
        }
    }

    @BeforeEach
    void prepare() throws IOException {
        // Authored offline transport fixture, not an authoritative TfL business-schema snapshot.
        try (var input = getClass().getResourceAsStream("/tfl/underground-status-response.json")) {
            raw = input.readAllBytes();
        }
        reset(projection);
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, underground_current_status, underground_current_line, underground_current_snapshot");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='transport-for-london') WHERE endpoint_key='tfl-underground-status'");
        bootstrap.initialize();
        jdbc.update("UPDATE source SET enabled = true WHERE source_key = 'transport-for-london'");
        jdbc.update("UPDATE source_endpoint SET enabled = true, qualification_status = 'QUALIFIED', qualification_record = 'Fixture owner approval', use_retention_policy = ? WHERE endpoint_key = 'tfl-underground-status'",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        handler = exchange -> respond(exchange, 200, "application/json; charset=utf-8", raw, false);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); }
            catch (Throwable failure) { serverFailure.set(failure); }
            finally { exchange.close(); }
        });
        server.start();
        client.target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/Line/Mode/tube/Status");
        client.original.set(null);
        client.invocations.set(0);
        client.transactionActive = false;
        client.failure = null;
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        executor.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS acquisition_test_reject ON evidence_artifact");
        jdbc.execute("DROP TRIGGER IF EXISTS acquisition_test_reject ON ingestion_run");
        jdbc.execute("DROP TRIGGER IF EXISTS acquisition_test_reject ON underground_current_line");
        jdbc.execute("DROP FUNCTION IF EXISTS acquisition_test_reject()");
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run, underground_current_status, underground_current_line, underground_current_snapshot");
        jdbc.update("UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='transport-for-london') WHERE endpoint_key='tfl-underground-status'");
        bootstrap.initialize();
        jdbc.update("UPDATE source_endpoint SET qualification_status = 'PENDING', qualification_record = NULL, enabled = true WHERE endpoint_key = 'tfl-underground-status'");
    }

    private void respond(HttpExchange exchange, int status, String type, byte[] body, boolean chunked) throws IOException {
        if (type != null) { exchange.getResponseHeaders().add("Content-Type", type); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : chunked ? 0 : body.length);
        exchange.getResponseBody().write(body);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id = ?", String.class, id); }
    private void failed(String code) {
        UUID id = acquisition.acquire().runId();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM ingestion_run WHERE id = ?", String.class, id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT length(failure_message) FROM ingestion_run WHERE id = ?", Integer.class, id)).isBetween(1, 1000);
        assertThat(count("evidence_artifact")).isZero();
        assertThat(count("ingestion_run")).isEqualTo(1);
        assertThat(client.invocations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failure_message FROM ingestion_run WHERE id=?", String.class, id))
                .doesNotContain("Café", "fixture-line", "Exception", "<html>");
    }

    @Test
    void successPreservesBytesMetadataHashAndCommittedStartedRunOutsideTransaction() throws Exception {
        handler = exchange -> {
            assertThat(count("ingestion_run")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run", String.class)).isEqualTo("STARTED");
            assertThat(count("evidence_artifact")).isZero();
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).isEqualTo(TflUndergroundHttp.USER_AGENT);
            assertThat(exchange.getRequestHeaders().getFirst("Accept-Encoding")).isEqualTo("identity");
            respond(exchange, 200, "application/json; charset=utf-8", raw, false);
        };
        Instant before = Instant.now();
        UUID id = acquisition.acquire().runId();
        Instant after = Instant.now();
        assertThat(serverFailure.get()).isNull();
        assertThat(state(id)).isEqualTo("SUCCESS");
        assertThat(client.transactionActive).isFalse();
        assertThat(client.invocations.get()).isEqualTo(1);
        assertThat(count("ingestion_run")).isEqualTo(1);
        assertThat(count("evidence_artifact")).isEqualTo(1);
        UUID artifactId = jdbc.queryForObject("SELECT id FROM evidence_artifact", UUID.class);
        var artifact = new TransactionTemplate(transactions).execute(status -> entityManager.find(EvidenceArtifact.class, artifactId));
        assertThat(artifact.getByteSize()).isEqualTo(raw.length);
        assertThat(artifact.getObservedAt()).isBetween(before, after);
        assertThat(jdbc.queryForObject("SELECT octet_length(payload) FROM evidence_artifact", Integer.class)).isEqualTo(raw.length);
        assertThat(jdbc.queryForObject("SELECT source_endpoint_id FROM ingestion_run WHERE id=?", UUID.class, id))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key='tfl-underground-status'", UUID.class));
        assertThat(client.original.get().uri().toString()).isEqualTo(jdbc.queryForObject("SELECT url FROM source_endpoint WHERE endpoint_key='tfl-underground-status'", String.class));
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, id)).containsExactly(raw);
        assertThat(jdbc.queryForObject("SELECT media_type FROM evidence_artifact", String.class)).isEqualTo("application/json; charset=utf-8");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM evidence_artifact", String.class)).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact a JOIN ingestion_run r ON r.id = a.ingestion_run_id JOIN source_endpoint e ON e.id = r.source_endpoint_id JOIN source s ON s.id = e.source_id WHERE r.id = ? AND a.observed_at BETWEEN r.started_at AND r.completed_at", Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE source_endpoint SET qualification_status = 'PENDING', qualification_record = NULL WHERE endpoint_key = 'tfl-underground-status'",
        "UPDATE source_endpoint SET enabled = false WHERE endpoint_key = 'tfl-underground-status'",
        "UPDATE source SET enabled = false WHERE source_key = 'transport-for-london'",
        "UPDATE source_endpoint SET qualification_record = '\u2003' WHERE endpoint_key='tfl-underground-status'",
        "UPDATE source_endpoint SET use_retention_policy = '\u2003' WHERE endpoint_key='tfl-underground-status'",
        "UPDATE source_endpoint SET source_id=(SELECT id FROM source WHERE source_key='gov-uk-bank-holidays') WHERE endpoint_key='tfl-underground-status'",
        "DELETE FROM source_endpoint WHERE endpoint_key='tfl-underground-status'"
    })
    void ineligibleConfigurationFailsBeforeRunAndNetwork(String change) {
        jdbc.update(change);
        assertThatThrownBy(acquisition::acquire).isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isZero();
        assertThat(count("ingestion_run")).isZero();
        assertThat(client.original.get()).isNull();
        assertThat(client.invocations.get()).isZero();
    }

    @Test
    void applicationBoundaryHasNoTargetArgumentAndRejectsAmbientTransaction() {
        assertThat(Arrays.stream(TflUndergroundAcquisition.class.getDeclaredMethods()).filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList())
                .allSatisfy(method -> assertThat(method.getParameterCount()).isZero());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> acquisition.acquire()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(requests.get()).isZero();
        assertThat(count("ingestion_run")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 304, 404, 500})
    void non200ResponsesFail(int status) {
        handler = exchange -> respond(exchange, status, "application/json", raw, false);
        failed("HTTP_STATUS");
    }

    @Test
    void redirectsAreNotFollowed() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/redirected");
            respond(exchange, 302, "application/json", raw, false);
        };
        failed("HTTP_STATUS");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "text/html", "not a media type", "application/problem+json"})
    void contentTypeContractRejectsUnexpectedOrMissingType(String type) {
        handler = exchange -> respond(exchange, 200, type.equals("missing") ? null : type, raw, false);
        failed("CONTENT_TYPE");
    }

    @Test
    void duplicateContentTypesFail() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, "application/json", raw, false);
        };
        failed("CONTENT_TYPE");
    }

    @Test
    void overlongContentTypeFails() {
        handler = exchange -> respond(exchange, 200, "application/json; note=" + "x".repeat(200), raw, false);
        failed("CONTENT_TYPE");
    }

    @Test
    void encodedBodyIsRejectedRatherThanDecompressed() {
        handler = exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            respond(exchange, 200, "application/json", raw, false);
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
        handler = exchange -> respond(exchange, 200, "application/json", new byte[TflUndergroundHttp.MAX_BODY_BYTES + 1], chunked);
        failed("RESPONSE_TOO_LARGE");
    }

    @Test
    void bodyAtLimitIsPreserved() {
        byte[] body = new byte[TflUndergroundHttp.MAX_BODY_BYTES];
        Arrays.fill(body, (byte) ' ');
        body[0] = '['; body[body.length - 1] = ']';
        handler = exchange -> respond(exchange, 200, "application/json", body, true);
        assertThat(state(acquisition.acquire().runId())).isEqualTo("SUCCESS");
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
            if (!bodyStall) { respond(exchange, 200, "application/json", raw, false); }
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
        assertThat(state(acquisition.acquire().runId())).isEqualTo("SUCCESS");
        assertThat(count("evidence_artifact")).isEqualTo(1);
    }

    @Test
    void failurePersistenceUnavailabilityPropagatesAndLeavesStartedHistory() {
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'FAILED' THEN RAISE EXCEPTION 'Injected failure recording outage'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE UPDATE ON ingestion_run FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        handler = exchange -> respond(exchange, 500, "application/json", raw, false);
        assertThatThrownBy(acquisition::acquire).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run", String.class)).isEqualTo("STARTED");
        assertThat(count("evidence_artifact")).isZero();
    }

    @Test
    void repeatedAndChangedResponsesCreateIndependentHistory() {
        UUID first = acquisition.acquire().runId();
        UUID second = acquisition.acquire().runId();
        handler = exchange -> respond(exchange, 200, "application/json", "{\"later\":true}".getBytes(StandardCharsets.UTF_8), false);
        UUID third = acquisition.acquire().runId();
        assertThat(first).isNotEqualTo(second).isNotEqualTo(third);
        assertThat(count("ingestion_run")).isEqualTo(3);
        assertThat(count("evidence_artifact")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, first)).containsExactly(raw);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id = ?", byte[].class, second)).containsExactly(raw);
        assertThat(state(first)).isEqualTo("SUCCESS");
        assertThat(state(second)).isEqualTo("SUCCESS");
        assertThat(state(third)).isEqualTo("SUCCESS");
    }

    @Test
    void crlfResponseBytesArePreservedWithoutNewlineNormalization() throws Exception {
        // Generate an alternate offline server response; production never decodes/transforms it.
        byte[] responseBytes = new String(raw, StandardCharsets.UTF_8).replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        handler = exchange -> respond(exchange, 200, "application/json; charset=utf-8", responseBytes, false);
        UUID run = acquisition.acquire().runId();
        assertThat(state(run)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id=?", byte[].class, run))
                .containsExactly(responseBytes);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM evidence_artifact WHERE ingestion_run_id=?", String.class, run))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(responseBytes)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=UTF-8", "Application/JSON; charset=\"utf-8\""})
    void jsonMediaTypeParametersAreAcceptedWithoutChangingStoredHeaderOrBytes(String mediaType) {
        handler = exchange -> respond(exchange, 200, mediaType, raw, false);
        UUID run = acquisition.acquire().runId();
        assertThat(state(run)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT media_type FROM evidence_artifact", String.class)).isEqualTo(mediaType);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact", byte[].class)).containsExactly(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.tfl.gov.uk/Line/Mode/dlr/Status", "https://api.tfl.gov.uk/Line/Mode/tube/Status?app_key=not-a-key",
        "http://api.tfl.gov.uk/Line/Mode/tube/Status", "https://example.invalid/Line/Mode/tube/Status",
        "https://www.gov.uk/bank-holidays.json"
    })
    void transportRejectsNoncanonicalTargetsBeforeClientInvocation(String url) {
        SourceEndpoint endpoint = SourceEndpoint.tflUnderground(Source.tfl());
        org.springframework.test.util.ReflectionTestUtils.setField(endpoint, "url", url);
        assertThatIllegalArgumentException().isThrownBy(() -> new TflUndergroundHttp(client, Duration.ofSeconds(1)).receive(endpoint));
        assertThat(client.invocations.get()).isZero();
        assertThat(requests.get()).isZero();
    }

    @Test
    void transportCannotBeConfiguredToFollowRedirects() {
        HttpClient unsafe = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(unsafe.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatIllegalArgumentException().isThrownBy(() -> new TflUndergroundHttp(unsafe, Duration.ofSeconds(1)))
                .withMessage("Redirects must be disabled");
    }

    @Test
    void unexpectedTransportFailureIsBoundedAndDoesNotLeakExceptionText() {
        client.failure = new IllegalStateException("PRIVATE_DIAGNOSTIC_MARKER");
        failed("TRANSPORT");
        assertThat(jdbc.queryForObject("SELECT failure_message FROM ingestion_run", String.class))
                .isEqualTo("HTTP transport could not complete");
    }

    @Test
    void persistedTflArtifactRejectsUpdatesDeletesAndDefensiveCopyMutation() {
        UUID run = acquisition.acquire().runId();
        UUID id = jdbc.queryForObject("SELECT id FROM evidence_artifact WHERE ingestion_run_id=?", UUID.class, run);
        EvidenceArtifact artifact = new TransactionTemplate(transactions).execute(status -> entityManager.find(EvidenceArtifact.class, id));
        byte[] copy = artifact.getPayload();
        copy[0] = '!';
        assertThat(artifact.getPayload()).containsExactly(raw);
        assertThat(artifact.getByteSize()).isEqualTo(raw.length);
        assertThatThrownBy(() -> jdbc.update("UPDATE evidence_artifact SET media_type='text/html' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_artifact WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE id=?", byte[].class, id)).containsExactly(raw);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM evidence_artifact WHERE id=?", String.class, id)).isEqualTo(artifact.getSha256());
    }

    private String bankHolidaysSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object(
                    'source', row_to_json(s), 'endpoint', row_to_json(e),
                    'runs', (SELECT json_agg(row_to_json(r) ORDER BY r.id) FROM ingestion_run r WHERE r.source_endpoint_id=e.id),
                    'artifacts', (SELECT json_agg(row_to_json(a) ORDER BY a.id) FROM evidence_artifact a
                        JOIN ingestion_run r ON r.id=a.ingestion_run_id WHERE r.source_endpoint_id=e.id))::text
                FROM source s JOIN source_endpoint e ON e.source_id=s.id
                WHERE e.endpoint_key='gov-uk-bank-holidays-json'
                """, String.class);
    }

    @Test
    void tflAcquisitionPreservesAllBankHolidaysConfigurationAndHistoricalEvidence() {
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline Bank Holidays approval', use_retention_policy='Independent Bank Holidays fixture policy' WHERE endpoint_key='gov-uk-bank-holidays-json'");
        UUID bankEndpoint = jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key='gov-uk-bank-holidays-json'", UUID.class);
        UUID bankRun = history.start(bankEndpoint);
        history.succeed(bankRun, "{\"offline\":true}".getBytes(StandardCharsets.UTF_8), "application/json", Instant.now());
        String before = bankHolidaysSnapshot();
        UUID tflRun = acquisition.acquire().runId();
        assertThat(state(tflRun)).isEqualTo("SUCCESS");
        assertThat(bankHolidaysSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT use_retention_policy FROM source_endpoint WHERE endpoint_key='tfl-underground-status'", String.class))
                .isEqualTo(SourceEndpoint.TFL_USE_RETENTION_POLICY);
    }

    private String currentState() {
        return jdbc.queryForObject("""
                SELECT json_build_object(
                    'snapshot',(SELECT json_agg(row_to_json(s)) FROM underground_current_snapshot s),
                    'lines',(SELECT json_agg(row_to_json(l) ORDER BY line_order) FROM underground_current_line l),
                    'statuses',(SELECT json_agg(row_to_json(t) ORDER BY line_id,status_order) FROM underground_current_status t))::text
                """, String.class);
    }

    @Test
    void successfulAcquisitionProjectsTheCommittedArtifactThroughProductionProjection() {
        var committedRunStatus = new AtomicReference<String>();
        var ambientTransaction = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            // JdbcTemplate uses its own pooled connection outside any transaction: it sees committed rows only.
            ambientTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            committedRunStatus.set(jdbc.queryForObject("SELECT r.status FROM evidence_artifact a JOIN ingestion_run r ON r.id=a.ingestion_run_id WHERE a.id=?",
                    String.class, invocation.<UUID>getArgument(0)));
            return invocation.callRealMethod();
        }).when(projection).projectEvidence(any());
        var result = acquisition.acquire();
        UUID artifactId = jdbc.queryForObject("SELECT id FROM evidence_artifact", UUID.class);
        assertThat(committedRunStatus.get()).isEqualTo("SUCCESS");
        assertThat(ambientTransaction.get()).isFalse();
        verify(projection).projectEvidence(artifactId);
        assertThat(state(result.runId())).isEqualTo("SUCCESS");
        assertThat(result.projection()).contains(new UndergroundEvidenceProjection.Result(APPLIED, null));
        var artifact = new TransactionTemplate(transactions).execute(status -> entityManager.find(EvidenceArtifact.class, artifactId));
        var current = states.current().orElseThrow();
        assertThat(current.evidenceArtifactId()).isEqualTo(artifactId);
        assertThat(current.observedAt()).isEqualTo(artifact.getObservedAt());
        assertThat(current.lines()).containsExactly(new UndergroundLineStatus("fixture-line", "Offline Underground fixture",
                List.of(new UndergroundOperationalStatus(9, "Minor Delays", Optional.of("  Test only: delays — Café, £5; punctuation!  ")))));
        assertThat(client.invocations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact", byte[].class)).containsExactly(raw);
    }

    @Test
    void failedAcquisitionNeverAttemptsProjection() {
        handler = exchange -> respond(exchange, 500, "application/json", raw, false);
        var httpFailure = acquisition.acquire();
        assertThat(state(httpFailure.runId())).isEqualTo("FAILED");
        assertThat(httpFailure.projection()).isEmpty();

        handler = exchange -> respond(exchange, 200, "application/json", raw, false);
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected artifact failure'; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE INSERT ON evidence_artifact FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        var persistenceFailure = acquisition.acquire();
        assertThat(state(persistenceFailure.runId())).isEqualTo("FAILED");
        assertThat(persistenceFailure.projection()).isEmpty();

        verify(projection, never()).projectEvidence(any());
        assertThat(count("evidence_artifact")).isZero();
        assertThat(states.current()).isEmpty();
    }

    @Test
    void malformedSuccessfulEvidenceIsRetainedAndLeavesPreviousCurrentState() {
        assertThat(acquisition.acquire().projection()).contains(new UndergroundEvidenceProjection.Result(APPLIED, null));
        String previous = currentState();
        byte[] malformed = "{\"later\":true}".getBytes(StandardCharsets.UTF_8);
        handler = exchange -> respond(exchange, 200, "application/json", malformed, false);
        var result = acquisition.acquire();
        assertThat(state(result.runId())).isEqualTo("SUCCESS");
        assertThat(result.projection()).contains(new UndergroundEvidenceProjection.Result(null, EVIDENCE_INVALID));
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id=?", byte[].class, result.runId()))
                .containsExactly(malformed);
        assertThat(count("evidence_artifact")).isEqualTo(2);
        assertThat(currentState()).isEqualTo(previous);
    }

    @Test
    void projectorFailureAfterDurableEvidenceIsReportedWithoutFailingTheAcquisition() {
        assertThat(acquisition.acquire().projection()).contains(new UndergroundEvidenceProjection.Result(APPLIED, null));
        String previous = currentState();
        jdbc.execute("CREATE FUNCTION acquisition_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected projection failure'; END $$");
        jdbc.execute("CREATE TRIGGER acquisition_test_reject BEFORE INSERT ON underground_current_line FOR EACH ROW EXECUTE FUNCTION acquisition_test_reject()");
        var result = acquisition.acquire();
        assertThat(state(result.runId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM ingestion_run WHERE id=?", String.class, result.runId())).isNull();
        assertThat(result.projection()).contains(new UndergroundEvidenceProjection.Result(null, PROJECTION_FAILED));
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE ingestion_run_id=?", byte[].class, result.runId()))
                .containsExactly(raw);
        assertThat(currentState()).isEqualTo(previous);
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    /** Test-only request rerouting: production still resolves the real persisted endpoint;
     * all HTTP/status/body/timeout behaviour uses the real JDK client and loopback server. */
    static final class LocalClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300))
                .followRedirects(Redirect.NEVER).build();
        private final AtomicReference<HttpRequest> original = new AtomicReference<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private URI target;
        private boolean transactionActive;
        private RuntimeException failure;
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            invocations.incrementAndGet();
            original.set(request);
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            assertThat(request.uri().toString()).isEqualTo(SourceEndpoint.TFL_UNDERGROUND_URL);
            assertThat(request.uri().getScheme()).isEqualTo("https");
            assertThat(request.uri().getRawQuery()).isNull();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.timeout()).isPresent();
            if (failure != null) { throw failure; }
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
