package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@Transactional
class EvidencePersistenceTest {
    private static final Instant START = Instant.parse("2026-10-02T10:00:00.123456Z");
    private static final byte[] PAYLOAD = "{\n  \"england-and-wales\": {\"events\": []}, \"raw\": \"£\"\n}\n".getBytes(StandardCharsets.UTF_8);
    @Autowired QualifiedSourceEndpoints qualifiedEndpoints;
    @Autowired IngestionRunRepository runs;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private UUID endpointId() {
        return jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key = ?", UUID.class,
                SourceEndpoint.BANK_HOLIDAYS_KEY);
    }

    private IngestionRun run() {
        // Test-only owner decision; evidence code uses only the source-owned read boundary.
        jdbc.update("UPDATE source_endpoint SET qualification_status = 'QUALIFIED', qualification_record = 'Fixture owner approval', use_retention_policy = 'Fixture approved retention'");
        IngestionRun run = runs.save(IngestionRun.start(qualifiedEndpoints.requireQualified(endpointId()), START));
        em.flush();
        return run;
    }

    private EvidenceArtifact artifact(IngestionRun run) {
        EvidenceArtifact artifact = new EvidenceArtifact(run, PAYLOAD, "application/json; charset=utf-8", START.plusSeconds(1));
        artifacts.append(artifact);
        return artifact;
    }

    @Test
    void lifecyclePayloadIntegrityAndProvenanceRoundTrip() throws Exception {
        IngestionRun run = run();
        assertThat(run.getStatus()).isEqualTo(RunStatus.STARTED);
        assertThat(run.getCompletedAt()).isNull();
        EvidenceArtifact artifact = artifact(run);
        run.succeed(START.plusSeconds(2));
        em.flush();
        em.clear();
        jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
        EvidenceArtifact loaded = artifacts.findById(artifact.getId()).orElseThrow();
        assertThat(loaded.getPayload()).containsExactly(PAYLOAD);
        assertThat(loaded.getMediaType()).isEqualTo("application/json; charset=utf-8");
        assertThat(loaded.getObservedAt()).isEqualTo(START.plusSeconds(1));
        assertThat(loaded.getSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PAYLOAD)));
        assertThat(loaded.getIngestionRun().getStartedAt()).isEqualTo(START);
        assertThat(loaded.getIngestionRun().getCompletedAt()).isEqualTo(START.plusSeconds(2));
        assertThat(loaded.getIngestionRun().getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(loaded.getIngestionRun().getFailureCode()).isNull();
        assertThat(loaded.getIngestionRun().getSourceEndpoint().getSource().getKey()).isEqualTo(Source.BANK_HOLIDAYS_KEY);
        assertThat(loaded.getIngestionRun().getSourceEndpoint().getId()).isEqualTo(endpointId());
    }

    @Test
    void failedRunPreservesBoundedFailureAndRejectsFurtherTransitions() {
        IngestionRun run = run();
        run.fail(START.plusSeconds(1), "TIMEOUT", "Response deadline exceeded");
        em.flush();
        em.clear();
        IngestionRun loaded = runs.findById(run.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(loaded.getCompletedAt()).isEqualTo(START.plusSeconds(1));
        assertThat(loaded.getFailureCode()).isEqualTo("TIMEOUT");
        assertThat(loaded.getFailureMessage()).isEqualTo("Response deadline exceeded");
        assertThatIllegalStateException().isThrownBy(() -> loaded.succeed(START.plusSeconds(3)));
    }

    @Test
    void laterAndIdenticalObservationsCoexistAndDefensiveCopiesCannotRewriteEvidence() {
        IngestionRun first = run();
        EvidenceArtifact earlier = artifact(first);
        first.succeed(START.plusSeconds(2));
        em.flush();
        IngestionRun second = run();
        EvidenceArtifact identical = artifact(second);
        EvidenceArtifact changed = new EvidenceArtifact(second, "{\"changed\":true}".getBytes(StandardCharsets.UTF_8), "application/json", START.plusSeconds(1));
        artifacts.append(changed);
        byte[] copy = earlier.getPayload();
        copy[0] = 0;
        em.flush();
        em.clear();
        assertThat(artifacts.findById(earlier.getId()).orElseThrow().getPayload()).containsExactly(PAYLOAD);
        assertThat(artifacts.findById(identical.getId()).orElseThrow().getSha256()).isEqualTo(earlier.getSha256());
        assertThat(artifacts.findById(changed.getId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence_artifact", Integer.class)).isEqualTo(3);
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void detachedArtifactCannotBeAppendedAsAnUpdate() {
        EvidenceArtifact artifact = artifact(run());
        em.clear();
        assertThatThrownBy(() -> artifacts.append(artifact)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE evidence_artifact SET payload = convert_to('new', 'UTF8')",
        "UPDATE evidence_artifact SET media_type = 'text/plain'",
        "DELETE FROM evidence_artifact",
        "UPDATE ingestion_run SET started_at = started_at + interval '1 second'",
        "DELETE FROM ingestion_run",
        "UPDATE source_endpoint SET source_id = (SELECT id FROM source WHERE source_key = 'other-source')"
    })
    void databaseProtectsHistory(String sql) {
        artifact(run());
        jdbc.update("INSERT INTO source VALUES (?, 'other-source', 'Other', 'UK_BANK_HOLIDAYS', true, 0)", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bulkJpaUpdateCannotRewriteArtifact() {
        artifact(run());
        assertThatThrownBy(() -> em.createQuery("update EvidenceArtifact set mediaType = 'text/plain'").executeUpdate())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsCompletedRunMutation() {
        IngestionRun run = run();
        run.succeed(START.plusSeconds(2));
        em.flush();
        assertThatThrownBy(() -> jdbc.update("UPDATE ingestion_run SET status = 'FAILED', failure_code = 'NEW', failure_message = 'Different event'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completionCannotPrecedePersistedObservation() {
        IngestionRun run = run();
        artifact(run);
        run.succeed(START);
        assertThatThrownBy(em::flush).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "NULL, NULL, 'STARTED', NULL, NULL",
        "now(), NULL, 'SUCCESS', NULL, NULL",
        "now(), NULL, 'FAILED', 'TIMEOUT', 'Failed'",
        "now(), now(), 'STARTED', NULL, NULL",
        "now(), now(), 'SUCCESS', 'FAIL', 'Contradiction'",
        "now(), now(), 'FAILED', NULL, 'Failed'",
        "now(), now(), 'FAILED', 'FAIL', ' '",
        "now(), now(), 'FAILED', repeat('x',101), 'Failed'",
        "now(), now(), 'FAILED', 'FAIL', repeat('x',1001)",
        "now(), now() - interval '1 second', 'SUCCESS', NULL, NULL",
        "now(), NULL, 'UNKNOWN', NULL, NULL"
    })
    void databaseRejectsInvalidLifecycle(String fields) {
        run();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ingestion_run (id, source_endpoint_id, started_at, completed_at, status, failure_code, failure_message, version) VALUES (?, ?, " + fields + ", 0)", UUID.randomUUID(), endpointId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "'00000000-0000-0000-0000-000000000000'"})
    void databaseRequiresExistingEndpoint(String endpoint) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ingestion_run VALUES (?, " + endpoint + ", now(), NULL, 'STARTED', NULL, NULL, 0)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "NULL, convert_to('raw','UTF8'), 'application/json', now(), encode(sha256(convert_to('raw','UTF8')), 'hex')",
        "'00000000-0000-0000-0000-000000000000', convert_to('raw','UTF8'), 'application/json', now(), encode(sha256(convert_to('raw','UTF8')), 'hex')"
    })
    void databaseRequiresExistingRun(String fields) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_artifact VALUES (?, " + fields + ")", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "NULL, 'application/json', now(), repeat('a',64)",
        "''::bytea, 'application/json', now(), repeat('a',64)",
        "convert_to('raw','UTF8'), NULL, now(), encode(sha256(convert_to('raw','UTF8')), 'hex')",
        "convert_to('raw','UTF8'), ' ', now(), encode(sha256(convert_to('raw','UTF8')), 'hex')",
        "convert_to('raw','UTF8'), repeat('x',201), now(), encode(sha256(convert_to('raw','UTF8')), 'hex')",
        "convert_to('raw','UTF8'), 'application/json', NULL, encode(sha256(convert_to('raw','UTF8')), 'hex')",
        "convert_to('raw','UTF8'), 'application/json', now(), NULL",
        "convert_to('raw','UTF8'), 'application/json', now(), repeat('a',64)",
        "convert_to('raw','UTF8'), 'application/json', '2000-01-01Z', encode(sha256(convert_to('raw','UTF8')), 'hex')"
    })
    void databaseRejectsInvalidEvidence(String fields) {
        IngestionRun run = run();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_artifact VALUES (?, ?, " + fields + ")", UUID.randomUUID(), run.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateRunIdentity() {
        run();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ingestion_run SELECT * FROM ingestion_run"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateArtifactIdentity() {
        artifact(run());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_artifact SELECT * FROM evidence_artifact"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE source_endpoint SET enabled = false", "UPDATE source SET enabled = false"})
    void disabledConfigurationPreventsNewRunsButDoesNotEraseHistory(String disable) {
        IngestionRun historical = run();
        jdbc.update(disable);
        em.clear();
        assertThat(runs.findById(historical.getId())).isPresent();
        assertThatIllegalStateException().isThrownBy(() -> qualifiedEndpoints.requireQualified(endpointId()));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ingestion_run VALUES (?, ?, ?, NULL, 'STARTED', NULL, NULL, 0)",
                UUID.randomUUID(), endpointId(), Timestamp.from(START)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourceLookupRejectsUnknownIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> qualifiedEndpoints.requireQualified(UUID.randomUUID()));
    }

    @Test
    void sourceBoundaryAndDatabaseRejectPendingEndpoint() {
        assertThatIllegalStateException().isThrownBy(() -> qualifiedEndpoints.requireQualified(endpointId()));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ingestion_run VALUES (?, ?, ?, NULL, 'STARTED', NULL, NULL, 0)",
                UUID.randomUUID(), endpointId(), Timestamp.from(START)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
