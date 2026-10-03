package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.acquisition.BankHolidaysAcquisition;
import info.lifeinuk.backend.bankholidays.GovUkBankHolidaysParser;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=bank_holidays_api_tests", "spring.flyway.default-schema=bank_holidays_api_tests",
    "spring.jpa.properties.hibernate.default_schema=bank_holidays_api_tests",
    "spring.datasource.hikari.schema=bank_holidays_api_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class BankHolidaysApiTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired IngestionRunRepository runs;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BankHolidaysEvidence selection;
    @MockitoBean BankHolidaysAcquisition acquisition;
    @MockitoSpyBean GovUkBankHolidaysParser parser;
    private byte[] raw;
    private UUID endpointId;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source SET enabled=true WHERE source_key = 'gov-uk-bank-holidays'");
        jdbc.update("UPDATE source_endpoint SET enabled=true, qualification_status='QUALIFIED', qualification_record='Offline fixture approval' WHERE endpoint_key = 'gov-uk-bank-holidays-json'");
        endpointId = jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key=?", UUID.class, SourceEndpoint.BANK_HOLIDAYS_KEY);
        try (var stream = getClass().getResourceAsStream("/bankholidays/representative.json")) {
            raw = stream.readAllBytes();
        }
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(em.contains(invocation.getArgument(0))).isFalse();
            return invocation.callRealMethod();
        }).when(parser).parse(any(EvidenceArtifact.class));
    }

    @AfterEach
    void noAcquisition() {
        verifyNoInteractions(acquisition);
    }

    private record Observation(UUID run, UUID artifact, Instant observedAt) { }

    private Observation store(byte[] bytes, Instant start, RunStatus status) {
        return new TransactionTemplate(transactions).execute(transaction -> {
            IngestionRun run = runs.save(IngestionRun.start(endpoints.requireQualified(endpointId), start));
            em.flush();
            EvidenceArtifact artifact = bytes == null ? null : new EvidenceArtifact(run, bytes, "application/json", start.plusSeconds(1));
            if (artifact != null) { artifacts.append(artifact); }
            if (status == RunStatus.SUCCESS) { run.succeed(start.plusSeconds(2)); }
            if (status == RunStatus.FAILED) { run.fail(start.plusSeconds(2), "FIXTURE", "Offline failure"); }
            em.flush();
            return new Observation(run.getId(), artifact == null ? null : artifact.getId(), start.plusSeconds(1));
        });
    }

    private HttpResponse<String> request(String method, String suffix) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/bank-holidays" + suffix))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    private String snapshot() {
        return jdbc.queryForObject("""
                select json_build_object(
                  'source', (select json_agg(row_to_json(s) order by id) from source s),
                  'endpoint', (select json_agg(row_to_json(e) order by id) from source_endpoint e),
                  'run', (select json_agg(row_to_json(r) order by id) from ingestion_run r),
                  'artifact', (select json_agg(row_to_json(a) order by id) from evidence_artifact a))::text
                """, String.class);
    }

    @Test
    void successExposesOnlyExplicitFactsAndProvenanceAndRepeatedGetsAreReadOnly() throws Exception {
        store(raw, START, RunStatus.SUCCESS);
        byte[] later = new String(raw, StandardCharsets.UTF_8).replace("2026", "2027").getBytes(StandardCharsets.UTF_8);
        Observation selected = store(later, START.plusSeconds(100), RunStatus.SUCCESS);
        String before = snapshot();
        var response = request("GET", "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        JsonNode actual = body(response);
        JsonNode expectedDivisions = json.readTree(later);
        assertThat(actual.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder("evidence", "divisions");
        assertThat(actual.get("evidence").size()).isEqualTo(2);
        assertThat(actual.at("/evidence/artifactId").stringValue()).isEqualTo(selected.artifact().toString());
        assertThat(actual.at("/evidence/observedAt").stringValue()).isEqualTo(selected.observedAt().toString());
        String[] divisions = {"england-and-wales", "scotland", "northern-ireland"};
        assertThat(actual.get("divisions").size()).isEqualTo(3);
        for (int index = 0; index < divisions.length; index++) {
            assertThat(actual.at("/divisions/" + index + "/division").stringValue()).isEqualTo(divisions[index]);
            assertThat(actual.at("/divisions/" + index + "/events")).isEqualTo(expectedDivisions.get(divisions[index]).get("events"));
            assertThat(actual.get("divisions").get(index).size()).isEqualTo(2);
        }
        assertThat(body(request("GET", ""))).isEqualTo(actual);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void runUuidBreaksCompletionTimestampTies() throws Exception {
        Observation first = store(raw, START, RunStatus.SUCCESS);
        Observation second = store(raw, START, RunStatus.SUCCESS);
        Observation expected = Arrays.stream(new Observation[]{first, second})
                .max(Comparator.comparing(observation -> observation.run().toString())).orElseThrow();
        assertThat(body(request("GET", "")).at("/evidence/artifactId").stringValue()).isEqualTo(expected.artifact().toString());
        assertThat(selection.latestSuccessful().orElseThrow().getId()).isEqualTo(expected.artifact());
    }

    @Test
    void laterCompletionWinsEvenWhenItsObservationIsOlder() throws Exception {
        Observation first = store(raw, START, RunStatus.SUCCESS);
        Observation second = new TransactionTemplate(transactions).execute(transaction -> {
            IngestionRun run = runs.save(IngestionRun.start(endpoints.requireQualified(endpointId), START.minusSeconds(10)));
            em.flush();
            EvidenceArtifact artifact = new EvidenceArtifact(run, raw, "application/json", START.minusSeconds(1));
            artifacts.append(artifact);
            run.succeed(START.plusSeconds(5));
            em.flush();
            return new Observation(run.getId(), artifact.getId(), artifact.getObservedAt());
        });
        assertThat(body(request("GET", "")).at("/evidence/artifactId").stringValue()).isEqualTo(second.artifact().toString());
        assertThat(second.artifact()).isNotEqualTo(first.artifact());
    }

    @Test
    void artifactObservationAndUuidBreakTiesWithinOneRun() throws Exception {
        UUID expected = new TransactionTemplate(transactions).execute(transaction -> {
            IngestionRun run = runs.save(IngestionRun.start(endpoints.requireQualified(endpointId), START));
            em.flush();
            EvidenceArtifact old = new EvidenceArtifact(run, raw, "application/json", START);
            EvidenceArtifact a = new EvidenceArtifact(run, raw, "application/json", START.plusSeconds(1));
            EvidenceArtifact b = new EvidenceArtifact(run, raw, "application/json", START.plusSeconds(1));
            artifacts.append(old); artifacts.append(a); artifacts.append(b);
            run.succeed(START.plusSeconds(2)); em.flush();
            return Arrays.stream(new UUID[]{a.getId(), b.getId()}).max(Comparator.comparing(UUID::toString)).orElseThrow();
        });
        assertThat(body(request("GET", "")).at("/evidence/artifactId").stringValue()).isEqualTo(expected.toString());
    }

    @Test
    void failedStartedAndSuccessfulRunsWithoutArtifactsCannotReplaceUsableHistory() throws Exception {
        Observation good = store(raw, START, RunStatus.SUCCESS);
        store(raw, START.plusSeconds(10), RunStatus.FAILED);
        store(raw, START.plusSeconds(20), RunStatus.STARTED);
        store(null, START.plusSeconds(30), RunStatus.SUCCESS);
        String before = snapshot();
        assertThat(body(request("GET", "")).at("/evidence/artifactId").stringValue()).isEqualTo(good.artifact().toString());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void noEvidenceReturnsBounded404AndNeverCreatesHistory() throws Exception {
        String before = snapshot();
        var response = request("GET", "");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response)).isEqualTo(json.readTree("""
                {"code":"BANK_HOLIDAYS_UNAVAILABLE","message":"No usable Bank Holidays evidence is available."}
                """));
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(parser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "STARTED", "SUCCESS_WITHOUT_ARTIFACT"})
    void ineligibleHistoryAloneReturnsNoEvidence(String history) throws Exception {
        store(history.equals("SUCCESS_WITHOUT_ARTIFACT") ? null : raw, START,
                history.equals("SUCCESS_WITHOUT_ARTIFACT") ? RunStatus.SUCCESS : RunStatus.valueOf(history));
        String before = snapshot();
        assertThat(request("GET", "").statusCode()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(parser);
    }

    @Test
    void malformedNewestEvidenceFailsWithoutFallbackOrMutationOrInternalDetails() throws Exception {
        store(raw, START, RunStatus.SUCCESS);
        store("{\"SECRET_MARKER\":true}".getBytes(StandardCharsets.UTF_8), START.plusSeconds(10), RunStatus.SUCCESS);
        String before = snapshot();
        var response = request("GET", "");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(json.readTree("""
                {"code":"BANK_HOLIDAYS_INVALID_EVIDENCE","message":"Stored Bank Holidays evidence could not be interpreted."}
                """));
        assertThat(response.body()).doesNotContain("SECRET_MARKER", "Exception", "payload", "sha256");
        assertThat(snapshot()).isEqualTo(before);
        verify(parser, times(1)).parse(any(EvidenceArtifact.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint-disabled", "source-disabled", "pending", "blank-approval"})
    void currentIneligibleConfigurationCannotBeExposed(String condition) throws Exception {
        store(raw, START, RunStatus.SUCCESS);
        if (condition.equals("endpoint-disabled")) { jdbc.update("UPDATE source_endpoint SET enabled=false WHERE endpoint_key = 'gov-uk-bank-holidays-json'"); }
        if (condition.equals("source-disabled")) { jdbc.update("UPDATE source SET enabled=false WHERE source_key = 'gov-uk-bank-holidays'"); }
        if (condition.equals("pending")) { jdbc.update("UPDATE source_endpoint SET qualification_status='PENDING', qualification_record=NULL WHERE endpoint_key = 'gov-uk-bank-holidays-json'"); }
        if (condition.equals("blank-approval")) {
            jdbc.update("UPDATE source_endpoint SET qualification_record=? WHERE endpoint_key = 'gov-uk-bank-holidays-json'", "\u2003");
        }
        String before = snapshot();
        assertThat(request("GET", "").statusCode()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(parser);
    }

    @Test
    void unrelatedSourceHistoryAndCallerSelectionInputAreIgnored() throws Exception {
        // Test-only fixture reassignment occurs before any observation; V2 freezes later ownership.
        UUID unrelated = UUID.randomUUID();
        jdbc.update("INSERT INTO source VALUES (?, 'unrelated-fixture', 'Unrelated fixture', 'UK_BANK_HOLIDAYS', true, 0)", unrelated);
        UUID original = jdbc.queryForObject("SELECT source_id FROM source_endpoint WHERE id=?", UUID.class, endpointId);
        jdbc.update("UPDATE source_endpoint SET source_id=? WHERE id=?", unrelated, endpointId);
        try {
            Observation wrong = store(raw, START, RunStatus.SUCCESS);
            String before = snapshot();
            var response = request("GET", "?url=https://example.invalid&source=unrelated-fixture&endpoint=" + endpointId + "&artifactId=" + wrong.artifact());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(snapshot()).isEqualTo(before);
            verifyNoInteractions(parser);
        } finally {
            // Only the isolated test schema is reset; historical production rows are never touched.
            jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
            jdbc.update("UPDATE source_endpoint SET source_id=? WHERE id=?", original, endpointId);
            jdbc.update("DELETE FROM source WHERE id=?", unrelated);
        }
    }

    @Test
    void callerCannotOverrideCanonicalObservation() throws Exception {
        Observation good = store(raw, START, RunStatus.SUCCESS);
        var response = request("GET", "?url=https://example.invalid&host=example.invalid&source=other&endpoint=" + UUID.randomUUID() + "&artifactId=" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).at("/evidence/artifactId").stringValue()).isEqualTo(good.artifact().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void writeMethodsAreNotExposed(String method) throws Exception {
        String before = snapshot();
        assertThat(request(method, "").statusCode()).isEqualTo(405);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(parser);
    }

    @Test
    void emptyDivisionArraysAndDuplicateEventsRemainFaithful() throws Exception {
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(raw);
        var events = (tools.jackson.databind.node.ArrayNode) tree.get("england-and-wales").get("events");
        events.add(events.get(0).deepCopy());
        ((tools.jackson.databind.node.ObjectNode) tree.get("scotland")).set("events", json.createArrayNode());
        store(json.writeValueAsBytes(tree), START, RunStatus.SUCCESS);
        var divisions = body(request("GET", "")).get("divisions");
        assertThat(divisions.get(1).get("events").isEmpty()).isTrue();
        assertThat(divisions.get(0).get("events").get(2)).isEqualTo(divisions.get(0).get("events").get(0));
    }
}
