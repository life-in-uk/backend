package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.schemas=evidence_provenance_tests", "spring.flyway.default-schema=evidence_provenance_tests",
    "spring.jpa.properties.hibernate.default_schema=evidence_provenance_tests", "spring.datasource.hikari.schema=evidence_provenance_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class EvidenceRequestProvenanceTest {
    private static final byte[] BYTES = "{\"offline\":true}".getBytes(StandardCharsets.UTF_8);
    @Autowired AcquisitionHistory history;
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void prepare() {
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='national-highways-road-closures'",
                SourceEndpoint.NATIONAL_HIGHWAYS_USE_RETENTION_POLICY);
    }

    private UUID startRun() { return history.start(endpoints.requireNationalHighwaysRoadClosures().getId()); }

    private static CapturedResponse response(String query, int page) {
        Instant requested = Instant.now();
        return new CapturedResponse(BYTES, "application/json", requested, Instant.now(), 200, query, page);
    }

    @Test
    void severalResponsesCommitTogetherWithSuccessAndRoundTripTheirProvenance() {
        UUID run = startRun();
        List<UUID> ids = history.succeedWithResponses(run, List.of(response("closureType=unplanned", 1),
                response("closureType=unplanned&pageCursor=2", 2)));
        assertThat(ids).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id=?", String.class, run)).isEqualTo("SUCCESS");
        var second = artifacts.findById(ids.get(1)).orElseThrow();
        assertThat(second.getRequestQuery()).isEqualTo("closureType=unplanned&pageCursor=2");
        assertThat(second.getPageNumber()).isEqualTo(2);
        assertThat(second.getHttpStatus()).isEqualTo(200);
        assertThat(second.getRequestedAt()).isBeforeOrEqualTo(second.getObservedAt());
        assertThat(second.getPayload()).containsExactly(BYTES);
        // V2 append-only protection covers the new provenance columns too.
        assertThatThrownBy(() -> jdbc.update("UPDATE evidence_artifact SET page_number=9 WHERE id=?", ids.get(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anyRejectedResponseRollsBackEveryArtifactAndLeavesTheRunStarted() {
        UUID run = startRun();
        assertThatThrownBy(() -> history.succeedWithResponses(run, List.of(response("closureType=unplanned", 1),
                response("closureType=unplanned&pageCursor=2", 1)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id=?", String.class, run)).isEqualTo("STARTED");
        assertThatIllegalArgumentException().isThrownBy(() -> history.succeedWithResponses(run, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"subscription-key=x", "Ocp-Apim-Subscription-Key=x", "a=1&API_KEY=x", "apikey=x", " ",
            "page0", "status99", "requested-after-observed", "requested-before-run"})
    void entityRejectsInvalidOrCredentialBearingProvenance(String violation) {
        UUID run = startRun();
        Instant now = Instant.now();
        CapturedResponse response = switch (violation) {
            case "page0" -> new CapturedResponse(BYTES, "application/json", now, now, 200, "q=1", 0);
            case "status99" -> new CapturedResponse(BYTES, "application/json", now, now, 99, "q=1", 1);
            case "requested-after-observed" -> new CapturedResponse(BYTES, "application/json", now.plusSeconds(1), now, 200, "q=1", 1);
            case "requested-before-run" -> new CapturedResponse(BYTES, "application/json", Instant.EPOCH, now, 200, "q=1", 1);
            default -> new CapturedResponse(BYTES, "application/json", now, now, 200, violation, 1);
        };
        assertThatIllegalArgumentException().isThrownBy(() -> history.succeedWithResponses(run, List.of(response)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "'closureType=unplanned', NULL, 200, now()",
        "NULL, 1, NULL, NULL",
        "'subscription-key=secret', 1, 200, now()",
        "'closureType=unplanned', 0, 200, now()",
        "'closureType=unplanned', 1, 700, now()",
        "'closureType=unplanned', 1, 200, now() + interval '1 hour'",
        "'   ', 1, 200, now()"
    })
    void databaseRejectsPartialInvalidOrCredentialBearingProvenance(String provenance) {
        UUID run = startRun();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO evidence_artifact (id, ingestion_run_id, payload, media_type, observed_at, sha256,
                    request_query, page_number, http_status, requested_at)
                VALUES (?, ?, convert_to('{}','UTF8'), 'application/json', now(), encode(sha256(convert_to('{}','UTF8')),'hex'),
                """ + provenance + ")", UUID.randomUUID(), run)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAllowsOneResponsePerPagePositionWithinARun() {
        UUID run = startRun();
        String insert = """
                INSERT INTO evidence_artifact (id, ingestion_run_id, payload, media_type, observed_at, sha256,
                    request_query, page_number, http_status, requested_at)
                VALUES (?, ?, convert_to('{}','UTF8'), 'application/json', now(), encode(sha256(convert_to('{}','UTF8')),'hex'),
                    'closureType=unplanned', 1, 200, now())
                """;
        jdbc.update(insert, UUID.randomUUID(), run);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), run)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v6UpgradePreservesExistingEvidenceWithAbsentProvenance() throws Exception {
        String schema = "v6_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("5").load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            String original = connection.getSchema();
            try {
                connection.setSchema(schema);
                try (var statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO source (id,source_key,display_name,scope,enabled,version)
                            VALUES ('00000000-0000-0000-0000-000000000001','transport-for-london','Transport for London','TFL_UNDERGROUND_STATUS',true,0)
                            """);
                    statement.execute("""
                            INSERT INTO source_endpoint (id,source_id,endpoint_key,url,qualification_status,qualification_record,attribution_reference,use_retention_policy,enabled,version)
                            VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','tfl-underground-status',
                                'https://api.tfl.gov.uk/Line/Mode/tube/Status','QUALIFIED','Offline owner decision','Offline terms','Offline policy',true,0)
                            """);
                    statement.execute("""
                            INSERT INTO ingestion_run (id,source_endpoint_id,started_at,status,version)
                            VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','2026-01-01T00:00:00Z','STARTED',0)
                            """);
                    statement.execute("""
                            INSERT INTO evidence_artifact (id,ingestion_run_id,payload,media_type,observed_at,sha256)
                            VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003',convert_to('[]','UTF8'),
                                'application/json','2026-01-01T00:00:01Z',encode(sha256(convert_to('[]','UTF8')),'hex'))
                            """);
                    statement.execute("UPDATE ingestion_run SET status='SUCCESS',completed_at='2026-01-01T00:00:02Z'");
                }
                String before = text(connection, "SELECT json_agg(json_build_object('id',id,'run',ingestion_run_id,'payload',encode(payload,'hex'),'media',media_type,'observed',observed_at,'sha',sha256))::text FROM evidence_artifact");
                assertThat(Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate().migrationsExecuted)
                        .isEqualTo(1);
                assertThat(text(connection, "SELECT json_agg(json_build_object('id',id,'run',ingestion_run_id,'payload',encode(payload,'hex'),'media',media_type,'observed',observed_at,'sha',sha256))::text FROM evidence_artifact"))
                        .isEqualTo(before);
                assertThat(text(connection, "SELECT count(*)::text FROM evidence_artifact WHERE request_query IS NULL AND page_number IS NULL AND http_status IS NULL AND requested_at IS NULL"))
                        .isEqualTo("1");
            } finally {
                connection.setSchema(original);
            }
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
