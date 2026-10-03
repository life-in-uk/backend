package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.acquisition.TflUndergroundAcquisition;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=underground_api_tests", "spring.flyway.default-schema=underground_api_tests",
    "spring.jpa.properties.hibernate.default_schema=underground_api_tests",
    "spring.datasource.hikari.schema=underground_api_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class UndergroundApiTest {
    private static final Instant OBSERVED = Instant.parse("2026-10-03T15:47:08.441419123Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UndergroundCurrentStates states;
    @MockitoBean UndergroundCurrentStateProjector projector;
    @MockitoBean TflUndergroundAcquisition acquisition;

    @BeforeEach
    void resetState() {
        reset(states);
        jdbc.execute("TRUNCATE underground_current_status, underground_current_line, underground_current_snapshot");
    }

    @AfterEach
    void noUpstreamCalls() { verifyNoInteractions(projector, acquisition); }

    private HttpResponse<String> request(String method) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/travel/underground"))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID snapshotFixture(boolean empty) {
        UUID evidence = UUID.randomUUID();
        jdbc.update("INSERT INTO underground_current_snapshot VALUES (1,?,?,?)", evidence, OBSERVED.getEpochSecond(), OBSERVED.getNano());
        if (!empty) {
            jdbc.update("INSERT INTO underground_current_line (line_id,line_name,line_order) VALUES (' victoria ',' Victoria — Café! ',0),('central','Central',1)");
            jdbc.update("""
                    INSERT INTO underground_current_status VALUES
                    (' victoria ',0,9,' Delays — £! ',' Reason… Café! '),
                    (' victoria ',1,10,'Good Service',NULL),
                    (' victoria ',2,7,'Other',''),
                    (' victoria ',3,9,' Delays — £! ',' Reason… Café! ')
                    """);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact WHERE id=?", Integer.class, evidence)).isZero();
        return evidence;
    }

    private String databaseSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object(
                 'snapshot',(SELECT json_agg(row_to_json(s)) FROM underground_current_snapshot s),
                 'lines',(SELECT json_agg(row_to_json(l) ORDER BY line_order) FROM underground_current_line l),
                 'statuses',(SELECT json_agg(row_to_json(t) ORDER BY line_id,status_order) FROM underground_current_status t),
                 'source',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM source s),
                 'endpoint',(SELECT json_agg(row_to_json(e) ORDER BY id) FROM source_endpoint e),
                 'runs',(SELECT json_agg(row_to_json(r) ORDER BY id) FROM ingestion_run r),
                 'artifacts',(SELECT json_agg(row_to_json(a) ORDER BY id) FROM evidence_artifact a))::text
                """, String.class);
    }

    @Test
    void exactStoredFactsAndOrderingArePublicWithoutRawEvidenceAndRepeatedGetsAreReadOnly() throws Exception {
        UUID internal = snapshotFixture(false);
        String before = databaseSnapshot();
        var response = request("GET");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"observedAt":"2026-10-03T15:47:08.441419123Z","lines":[
                 {"lineId":" victoria ","lineName":" Victoria — Café! ","statuses":[
                  {"severity":9,"description":" Delays — £! ","reason":" Reason… Café! "},
                  {"severity":10,"description":"Good Service","reason":null},
                  {"severity":7,"description":"Other","reason":""},
                  {"severity":9,"description":" Delays — £! ","reason":" Reason… Café! "}]},
                 {"lineId":"central","lineName":"Central","statuses":[]}]}
                """));
        assertThat(response.body()).doesNotContain(internal.toString(), "evidenceArtifactId", "sha256", "payload", "ingestionRun");
        assertThat(json.readTree(request("GET").body())).isEqualTo(json.readTree(response.body()));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verify(states, times(2)).current();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void noStateReturnsOnlyBounded404WithoutCreatingAnything() throws Exception {
        String before = databaseSnapshot();
        var response = request("GET");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"code":"UNDERGROUND_UNAVAILABLE","message":"No Underground Current State is available."}
                """));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verify(states).current();
    }

    @Test
    void persistedEmptySnapshotReturns200WithExactObservation() throws Exception {
        snapshotFixture(true);
        String before = databaseSnapshot();
        var response = request("GET");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"observedAt":"2026-10-03T15:47:08.441419123Z","lines":[]}
                """));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void internalFailureReturnsSafeBounded500() throws Exception {
        String before = databaseSnapshot();
        doThrow(new IllegalStateException("SECRET SQL /local/path password=hidden")).when(states).current();
        var response = request("GET");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
                {"code":"UNDERGROUND_READ_FAILED","message":"Underground Current State could not be read."}
                """));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void writeMethodsAreNotExposed(String method) throws Exception {
        String before = databaseSnapshot();
        assertThat(request(method).statusCode()).isEqualTo(405);
        verifyNoInteractions(states);
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void responseCollectionsAreDefensivelyImmutable() {
        var statuses = new java.util.ArrayList<>(List.of(new UndergroundResponse.Status(9,"Delays",null)));
        var line = new UndergroundResponse.Line("central","Central",statuses);
        var lines = new java.util.ArrayList<>(List.of(line));
        var response = new UndergroundResponse(OBSERVED,lines);
        statuses.clear(); lines.clear();
        assertThat(response.lines()).containsExactly(line);
        assertThat(line.statuses()).hasSize(1);
        assertThatThrownBy(() -> response.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> line.statuses().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
