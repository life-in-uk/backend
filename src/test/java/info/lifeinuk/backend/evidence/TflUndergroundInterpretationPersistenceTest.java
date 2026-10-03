package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import info.lifeinuk.backend.underground.TflUndergroundStatusParser;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Isolated, committed fixtures; parsing occurs detached and outside every transaction. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=tfl_interpretation_tests", "spring.flyway.default-schema=tfl_interpretation_tests",
    "spring.jpa.properties.hibernate.default_schema=tfl_interpretation_tests", "spring.datasource.hikari.schema=tfl_interpretation_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class TflUndergroundInterpretationPersistenceTest {
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired IngestionRunRepository runs;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private UUID persist(byte[] bytes, Instant start) {
        return new TransactionTemplate(transactions).execute(status -> {
            var run = runs.save(IngestionRun.start(endpoints.requireTflUnderground(), start));
            entityManager.flush();
            var artifact = new EvidenceArtifact(run, bytes, "application/json; charset=utf-8", start.plusSeconds(1));
            artifacts.append(artifact);
            run.succeed(start.plusSeconds(2));
            entityManager.flush();
            return artifact.getId();
        });
    }

    private EvidenceArtifact detached(UUID id) {
        return new TransactionTemplate(transactions).execute(status -> {
            var artifact = artifacts.findById(id).orElseThrow();
            entityManager.clear();
            return artifact;
        });
    }

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT json_agg(row_to_json(history) ORDER BY artifact_id)::text FROM (
                    SELECT a.id AS artifact_id, row_to_json(a) AS artifact,
                           row_to_json(r) AS run, row_to_json(e) AS endpoint, row_to_json(s) AS source
                    FROM evidence_artifact a JOIN ingestion_run r ON r.id = a.ingestion_run_id
                    JOIN source_endpoint e ON e.id = r.source_endpoint_id JOIN source s ON s.id = e.source_id
                ) history
                """, String.class);
    }

    @Test
    void detachedHistoricalInterpretationLeavesEveryPersistedColumnUnchanged() throws IOException {
        byte[] firstBytes;
        try (var input = getClass().getResourceAsStream("/tfl/underground-status-response.json")) {
            firstBytes = input.readAllBytes();
        }
        byte[] laterBytes = new String(firstBytes, StandardCharsets.UTF_8).replace("Minor Delays", "Later source status")
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy=? WHERE endpoint_key='tfl-underground-status'",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        UUID first = persist(firstBytes, Instant.parse("2026-01-01T00:00:00Z"));
        UUID later = persist(laterBytes, Instant.parse("2026-01-02T00:00:00Z"));
        // Present-day eligibility does not control interpretation of a historical observation.
        jdbc.update("UPDATE source_endpoint SET enabled=false WHERE endpoint_key='tfl-underground-status'");
        jdbc.update("UPDATE source SET enabled=false WHERE source_key='transport-for-london'");
        String before = snapshot();
        var oldArtifact = detached(first);
        var laterArtifact = detached(later);
        assertThat(Hibernate.isInitialized(oldArtifact.getIngestionRun())).isFalse();
        assertThat(Hibernate.isInitialized(laterArtifact.getIngestionRun())).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var parser = new TflUndergroundStatusParser();
        var oldResult = parser.parse(oldArtifact);
        var laterResult = parser.parse(laterArtifact);
        assertThat(oldResult.evidenceArtifactId()).isEqualTo(first);
        assertThat(laterResult.evidenceArtifactId()).isEqualTo(later);
        assertThat(oldResult.observedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:01Z"));
        assertThat(laterResult.observedAt()).isEqualTo(Instant.parse("2026-01-02T00:00:01Z"));
        assertThat(oldResult.lines().getFirst().statuses().getFirst().description()).isEqualTo("Minor Delays");
        assertThat(laterResult.lines().getFirst().statuses().getFirst().description()).isEqualTo("Later source status");
        assertThat(parser.parse(oldArtifact)).isEqualTo(oldResult);
        assertThat(Hibernate.isInitialized(oldArtifact.getIngestionRun())).isFalse();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(detached(first).getPayload()).containsExactly(firstBytes);
        assertThat(detached(later).getPayload()).containsExactly(laterBytes);
        assertThat(oldArtifact.getByteSize()).isEqualTo(firstBytes.length);
        assertThat(detached(first).getSha256()).isEqualTo(oldArtifact.getSha256());
    }
}
