package info.lifeinuk.backend.evidence;

import static org.assertj.core.api.Assertions.*;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V11 against a real, isolated PostgreSQL: only policy-eligible Travel evidence can be removed, only through
 * expire_travel_evidence, and the current Underground and Roads state stay readable through the public API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=travel_evidence_expiry_tests", "spring.flyway.default-schema=travel_evidence_expiry_tests",
    "spring.jpa.properties.hibernate.default_schema=travel_evidence_expiry_tests",
    "spring.datasource.hikari.schema=travel_evidence_expiry_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class TravelEvidenceExpiryMigrationTest {
    private static final String TFL = "tfl-underground-status";
    private static final String NH = "national-highways-road-closures";
    private static final String BANK_HOLIDAYS = "gov-uk-bank-holidays-json";
    private final Instant now = Instant.now();
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @LocalServerPort int port;

    record Expired(int runs, int artifacts, long bytes) { }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE underground_current_status, underground_current_line, underground_current_snapshot, roads_current_snapshot");
        // Fixture reset only: TRUNCATE is not a row DELETE, so the triggers under test are not involved.
        jdbc.execute("TRUNCATE evidence_artifact, ingestion_run");
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline test decision', "
                + "use_retention_policy='Offline test policy', enabled=true");
        jdbc.update("UPDATE source SET enabled=true");
    }

    private Expired expire(int batch, boolean dryRun) {
        return transactions.execute(status -> jdbc.queryForObject(
                "SELECT runs, artifacts, payload_bytes FROM expire_travel_evidence(?, ?)",
                (rs, row) -> new Expired(rs.getInt(1), rs.getInt(2), rs.getLong(3)), batch, dryRun));
    }

    private UUID endpoint(String key) {
        return jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key=?", UUID.class, key);
    }

    /** A historical run started {@code age} ago, optionally with one artifact, completed with the given status. */
    private UUID run(String endpointKey, String status, Duration age, boolean artifact) throws Exception {
        UUID id = UUID.randomUUID();
        Instant started = now.minus(age);
        jdbc.update("INSERT INTO ingestion_run (id, source_endpoint_id, started_at, status, version) VALUES (?,?,?,'STARTED',0)",
                id, endpoint(endpointKey), Timestamp.from(started));
        if (artifact) {
            byte[] payload = ("{\"run\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO evidence_artifact (id, ingestion_run_id, payload, media_type, observed_at, sha256) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), id, payload, "application/json", Timestamp.from(started.plusSeconds(1)),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        }
        Timestamp completed = Timestamp.from(started.plusSeconds(2));
        switch (status) {
            case "SUCCESS" -> jdbc.update("UPDATE ingestion_run SET status='SUCCESS', completed_at=?, version=1 WHERE id=?", completed, id);
            case "FAILED" -> jdbc.update("UPDATE ingestion_run SET status='FAILED', completed_at=?, failure_code='TIMEOUT', "
                    + "failure_message='HTTP request timed out', version=1 WHERE id=?", completed, id);
            default -> { }
        }
        return id;
    }

    private boolean exists(UUID run) {
        return jdbc.queryForObject("SELECT count(*) FROM ingestion_run WHERE id=?", Integer.class, run) == 1;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void retentionBoundariesAreTfl72HoursNh7DaysFailures30Days() throws Exception {
        UUID tflExpired = run(TFL, "SUCCESS", Duration.ofHours(73), true);
        UUID tflRetained = run(TFL, "SUCCESS", Duration.ofHours(71), true);
        UUID nhExpired = run(NH, "SUCCESS", Duration.ofDays(8), true);
        UUID nhRetained = run(NH, "SUCCESS", Duration.ofDays(6), true);
        UUID failedExpired = run(NH, "FAILED", Duration.ofDays(31), false);
        UUID failedRetained = run(TFL, "FAILED", Duration.ofDays(29), false);
        UUID startedExpired = run(TFL, "STARTED", Duration.ofDays(31), false);
        UUID startedRetained = run(NH, "STARTED", Duration.ofDays(8), false);
        UUID bankHolidays = run(BANK_HOLIDAYS, "SUCCESS", Duration.ofDays(400), true);

        var dry = expire(500, true);
        assertThat(dry.runs()).isEqualTo(4);
        assertThat(dry.artifacts()).isEqualTo(2);
        assertThat(dry.bytes()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_run", Integer.class)).as("dry run deletes nothing").isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT sum(runs) FROM travel_evidence_expiry_candidates", Integer.class)).isEqualTo(4);

        var applied = expire(500, false);
        assertThat(applied).isEqualTo(dry);
        for (UUID gone : List.of(tflExpired, nhExpired, failedExpired, startedExpired))
            assertThat(exists(gone)).as("expired %s", gone).isFalse();
        for (UUID kept : List.of(tflRetained, nhRetained, failedRetained, startedRetained, bankHolidays))
            assertThat(exists(kept)).as("retained %s", kept).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isEqualTo(3);
        assertThat(expire(500, false).runs()).isZero();
    }

    @Test
    void currentProjectedStateAndItsEvidenceRemainAvailable() throws Exception {
        UUID tflCurrent = run(TFL, "SUCCESS", Duration.ofDays(40), true);
        UUID nhCurrent = run(NH, "SUCCESS", Duration.ofDays(40), true);
        UUID tflOld = run(TFL, "SUCCESS", Duration.ofDays(40), true);
        UUID artifact = jdbc.queryForObject("SELECT id FROM evidence_artifact WHERE ingestion_run_id=?", UUID.class, tflCurrent);
        jdbc.update("INSERT INTO underground_current_snapshot VALUES (1,?,?,0)", artifact, now.minus(Duration.ofDays(40)).getEpochSecond());
        jdbc.update("INSERT INTO underground_current_line (line_id,line_name,line_order) VALUES ('victoria','Victoria',0)");
        jdbc.update("INSERT INTO underground_current_status VALUES ('victoria',0,10,'Good Service',NULL)");
        jdbc.update("INSERT INTO roads_current_snapshot VALUES (1,?,?,?,0,?,1,'[]'::jsonb)", nhCurrent, endpoint(NH),
                now.minus(Duration.ofDays(40)).getEpochSecond(), Timestamp.from(now.minus(Duration.ofDays(40))));

        assertThat(expire(500, false).runs()).isEqualTo(1);
        assertThat(exists(tflOld)).isFalse();
        assertThat(exists(tflCurrent)).isTrue();
        assertThat(exists(nhCurrent)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact WHERE id=?", Integer.class, artifact)).isEqualTo(1);

        var underground = get("/api/travel/underground");
        assertThat(underground.statusCode()).isEqualTo(200);
        assertThat(underground.body()).contains("\"lineId\":\"victoria\"", "Good Service");
        var roads = get("/api/travel/roads?lat=52&lon=-1");
        assertThat(roads.statusCode()).isEqualTo(200);
        assertThat(roads.body()).contains("\"snapshotAt\"", "\"disruptions\":[]");
    }

    @Test
    void ordinaryDeletesRemainRejectedEvenWithTheExpiryFlag() throws Exception {
        UUID recent = run(TFL, "SUCCESS", Duration.ofHours(1), true);
        UUID old = run(TFL, "SUCCESS", Duration.ofDays(9), true);
        UUID bankHolidays = run(BANK_HOLIDAYS, "SUCCESS", Duration.ofDays(400), true);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM evidence_artifact WHERE ingestion_run_id=?", old))
                .hasMessageContaining("Evidence is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ingestion_run WHERE id=?", old))
                .hasMessageContaining("Historical runs cannot be deleted");
        // Setting the transaction flag by hand cannot widen the policy: the triggers re-check every row.
        for (UUID protectedRun : List.of(recent, bankHolidays)) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('life_in_uk.travel_evidence_expiry','on',true)", String.class);
                jdbc.update("DELETE FROM evidence_artifact WHERE ingestion_run_id=?", protectedRun);
            })).hasMessageContaining("Evidence is append-only");
        }
        // A run cannot be removed while it still has artifacts, even when eligible.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('life_in_uk.travel_evidence_expiry','on',true)", String.class);
            jdbc.update("DELETE FROM ingestion_run WHERE id=?", old);
        })).hasMessageContaining("Historical runs cannot be deleted");
        // Updates keep their original rule.
        assertThatThrownBy(() -> jdbc.update("UPDATE evidence_artifact SET media_type='text/plain' WHERE ingestion_run_id=?", old))
                .hasMessageContaining("Evidence is append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT coalesce(current_setting('life_in_uk.travel_evidence_expiry', true), '')", String.class))
                .as("the flag is transaction-local").isNotEqualTo("on");
    }

    @Test
    void deletionRequiresAnExplicitlyGrantedPrivilegeNotJustTableDelete() throws Exception {
        UUID old = run(TFL, "SUCCESS", Duration.ofDays(9), true);
        String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
        // A non-owner role with the same table DML a runtime role receives (publication V14).
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='expiry_probe') "
                + "THEN CREATE ROLE expiry_probe NOLOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO expiry_probe");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ingestion_run, evidence_artifact, source_endpoint, "
                + "underground_current_snapshot, roads_current_snapshot TO expiry_probe");
        jdbc.execute("REVOKE EXECUTE ON FUNCTION expire_travel_evidence(integer, boolean) FROM expiry_probe");

        // Without EXECUTE: setting the flag and deleting an eligible row directly is refused,
        // and so is calling the expiry function.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE expiry_probe");
            jdbc.queryForObject("SELECT set_config('life_in_uk.travel_evidence_expiry','on',true)", String.class);
            jdbc.update("DELETE FROM evidence_artifact WHERE ingestion_run_id=?", old);
        })).hasMessageContaining("Evidence is append-only");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE expiry_probe");
            jdbc.queryForObject("SELECT runs FROM expire_travel_evidence(500, false)", Integer.class);
        })).rootCause().hasMessageContaining("permission denied for function expire_travel_evidence");
        assertThat(exists(old)).isTrue();

        // Only after an administrator explicitly grants EXECUTE can that role expire eligible evidence.
        jdbc.execute("GRANT EXECUTE ON FUNCTION expire_travel_evidence(integer, boolean) TO expiry_probe");
        Integer expired = transactions.execute(status -> {
            jdbc.execute("SET LOCAL ROLE expiry_probe");
            return jdbc.queryForObject("SELECT runs FROM expire_travel_evidence(500, false)", Integer.class);
        });
        assertThat(expired).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        jdbc.execute("REVOKE EXECUTE ON FUNCTION expire_travel_evidence(integer, boolean) FROM expiry_probe");
        assertThat(jdbc.queryForObject("SELECT has_function_privilege('public', "
                + "'expire_travel_evidence(integer, boolean)', 'EXECUTE')", Boolean.class))
                .as("PUBLIC cannot execute the expiry function").isFalse();
    }

    @Test
    void expiryIsBatchedOldestFirstAndValidatesItsBatchSize() throws Exception {
        for (int day = 4; day <= 8; day++) run(TFL, "SUCCESS", Duration.ofDays(day), true);
        assertThat(expire(2, false).runs()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT min(started_at) > now() - interval '6 days 12 hours' FROM ingestion_run", Boolean.class))
                .as("the two oldest runs (7 and 8 days) went first").isTrue();
        assertThat(expire(2, false).runs()).isEqualTo(2);
        assertThat(expire(2, false).runs()).isEqualTo(1);
        assertThat(expire(2, false).runs()).isZero();
        assertThatThrownBy(() -> expire(0, true)).hasMessageContaining("batch_size");
        assertThatThrownBy(() -> expire(5001, true)).hasMessageContaining("batch_size");
    }
}
