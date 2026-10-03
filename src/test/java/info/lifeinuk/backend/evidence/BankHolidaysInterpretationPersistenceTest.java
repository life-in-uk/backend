package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.bankholidays.GovUkBankHolidaysParser;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** Fixture persistence uses evidence-owned boundaries, never the development database. */
@SpringBootTest
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
@Transactional
class BankHolidaysInterpretationPersistenceTest {
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired IngestionRunRepository runs;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired GovUkBankHolidaysParser parser;

    private EvidenceArtifact persist(UUID endpointId, byte[] payload, Instant start) {
        IngestionRun run = runs.save(IngestionRun.start(endpoints.requireQualified(endpointId), start));
        entityManager.flush();
        EvidenceArtifact artifact = new EvidenceArtifact(run, payload, "application/json; charset=utf-8", start.plusSeconds(1));
        artifacts.append(artifact);
        run.succeed(start.plusSeconds(2));
        entityManager.flush();
        return artifact;
    }

    private String historySnapshot() {
        // Include every stored field, including payload, source policy and run history.
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
    void persistedHistoricalArtifactsAreIndependentlyParsedWithoutAnyStoredMutation() throws IOException {
        byte[] firstBytes;
        try (var input = getClass().getResourceAsStream("/bankholidays/representative.json")) {
            firstBytes = input.readAllBytes();
        }
        byte[] laterBytes = new String(firstBytes, StandardCharsets.UTF_8).replace("2026", "2027").getBytes(StandardCharsets.UTF_8);
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED', qualification_record='Offline fixture approval', use_retention_policy='Offline fixture retention'");
        UUID endpointId = jdbc.queryForObject("SELECT id FROM source_endpoint WHERE endpoint_key=?", UUID.class, SourceEndpoint.BANK_HOLIDAYS_KEY);
        EvidenceArtifact first = persist(endpointId, firstBytes, Instant.parse("2026-01-01T00:00:00Z"));
        EvidenceArtifact later = persist(endpointId, laterBytes, Instant.parse("2027-01-01T00:00:00Z"));
        String firstHash = first.getSha256();
        String laterHash = later.getSha256();
        // Current acquisition eligibility must not prevent interpretation of an old observation.
        jdbc.update("UPDATE source_endpoint SET enabled=false");
        entityManager.clear();
        String before = historySnapshot();
        EvidenceArtifact loadedFirst = artifacts.findById(first.getId()).orElseThrow();
        EvidenceArtifact loadedLater = artifacts.findById(later.getId()).orElseThrow();
        entityManager.detach(loadedFirst);
        entityManager.detach(loadedLater);
        var oldResult = parser.parse(loadedFirst);
        var laterResult = parser.parse(loadedLater);
        assertThat(oldResult.evidenceArtifactId()).isEqualTo(first.getId());
        assertThat(laterResult.evidenceArtifactId()).isEqualTo(later.getId());
        assertThat(oldResult.facts().getFirst().date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(laterResult.facts().getFirst().date()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(parser.parse(loadedFirst)).isEqualTo(oldResult);
        entityManager.flush();
        entityManager.clear();
        assertThat(historySnapshot()).isEqualTo(before);
        assertThat(artifacts.findById(first.getId()).orElseThrow().getPayload()).containsExactly(firstBytes);
        assertThat(artifacts.findById(later.getId()).orElseThrow().getPayload()).containsExactly(laterBytes);
        assertThat(artifacts.findById(first.getId()).orElseThrow().getSha256()).isEqualTo(firstHash);
        assertThat(artifacts.findById(later.getId()).orElseThrow().getSha256()).isEqualTo(laterHash);
    }
}
