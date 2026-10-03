package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.source.SourceEndpoint;
import info.lifeinuk.backend.support.IsolatedPostgres;
import info.lifeinuk.backend.underground.*;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.schemas=underground_projection_integrity", "spring.flyway.default-schema=underground_projection_integrity",
    "spring.jpa.properties.hibernate.default_schema=underground_projection_integrity", "spring.datasource.hikari.schema=underground_projection_integrity"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class UndergroundProjectionIntegrityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired AcquisitionHistory history;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired UndergroundCurrentStateProjector projector;
    @Autowired UndergroundCurrentStates states;

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT json_agg(row_to_json(history) ORDER BY artifact_id)::text FROM (
                    SELECT a.id AS artifact_id,row_to_json(a) AS artifact,row_to_json(r) AS run,
                           row_to_json(e) AS endpoint,row_to_json(s) AS source
                    FROM evidence_artifact a JOIN ingestion_run r ON r.id=a.ingestion_run_id
                    JOIN source_endpoint e ON e.id=r.source_endpoint_id JOIN source s ON s.id=e.source_id
                ) history
                """, String.class);
    }

    @Test
    void suppliedParserInterpretationProjectsWithoutChangingAnyEvidenceHistoryOrConfiguration() throws IOException {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/tfl/underground-status-response.json")) { bytes = input.readAllBytes(); }
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED',qualification_record='Offline decision',use_retention_policy=? WHERE endpoint_key='tfl-underground-status'",
                SourceEndpoint.TFL_USE_RETENTION_POLICY);
        var run = history.start(endpoints.requireTflUnderground().getId());
        var id = history.succeed(run, bytes, "application/json", Instant.now());
        var artifact = artifacts.findById(id).orElseThrow();
        var input = new TflUndergroundStatusParser().parse(artifact);
        var unchanged = new UndergroundStatusInterpretation(input.evidenceArtifactId(), input.observedAt(), input.lines());
        String before = snapshot();
        assertThat(projector.project(input)).isEqualTo(UndergroundCurrentStateProjector.Outcome.APPLIED);
        assertThat(projector.project(input)).isEqualTo(UndergroundCurrentStateProjector.Outcome.REPLAYED);
        assertThat(states.current()).contains(new UndergroundCurrentState(id, artifact.getObservedAt(), input.lines()));
        assertThat(input).isEqualTo(unchanged);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT payload FROM evidence_artifact WHERE id=?", byte[].class, id)).containsExactly(bytes);
    }
}
