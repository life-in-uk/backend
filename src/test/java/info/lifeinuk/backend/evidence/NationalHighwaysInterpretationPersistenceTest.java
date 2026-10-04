package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.roads.NationalHighwaysRoadClosuresParser;
import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import info.lifeinuk.backend.support.IsolatedPostgres;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.schemas=nh_interpretation_tests", "spring.flyway.default-schema=nh_interpretation_tests",
    "spring.jpa.properties.hibernate.default_schema=nh_interpretation_tests",
    "spring.datasource.hikari.schema=nh_interpretation_tests"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class NationalHighwaysInterpretationPersistenceTest {
    @Autowired QualifiedSourceEndpoints endpoints;
    @Autowired EntityManager em;
    @Autowired IngestionRunRepository runs;
    @Autowired EvidenceArtifacts artifacts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT json_agg(row_to_json(history) ORDER BY artifact_id)::text FROM (
                SELECT a.id artifact_id,row_to_json(a) artifact,row_to_json(r) run,
                row_to_json(e) endpoint,row_to_json(s) source FROM evidence_artifact a
                JOIN ingestion_run r ON r.id=a.ingestion_run_id JOIN source_endpoint e ON e.id=r.source_endpoint_id
                JOIN source s ON s.id=e.source_id) history
                """,String.class);
    }

    @Test
    void detachedPagesRetainLogicalRunGroupingAndLeaveEveryPersistedColumnUnchanged() throws Exception {
        byte[] bytes;
        try(var in=getClass().getResourceAsStream("/nationalhighways/interpretation-page.json")){bytes=in.readAllBytes();}
        jdbc.update("UPDATE source_endpoint SET qualification_status='QUALIFIED',qualification_record='Offline approval',use_retention_policy='Offline terms policy' WHERE endpoint_key='national-highways-road-closures'");
        var tx=new TransactionTemplate(transactions);
        List<UUID> ids=tx.execute(status->{
            var run=runs.save(IngestionRun.start(endpoints.requireNationalHighwaysRoadClosures(),Instant.EPOCH));em.flush();
            var first=new EvidenceArtifact(run,new CapturedResponse(bytes,"application/json",Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1),200,"closureType=unplanned",1));
            var second=new EvidenceArtifact(run,new CapturedResponse(bytes,"application/json",Instant.EPOCH.plusSeconds(2),
                    Instant.EPOCH.plusSeconds(3),200,"closureType=unplanned&pageCursor=offline-cursor",2));
            artifacts.append(first);artifacts.append(second);run.succeed(Instant.EPOCH.plusSeconds(4));em.flush();
            return List.of(first.getId(),second.getId());
        });
        String before=snapshot();
        var detached=tx.execute(status->{
            var result=em.createQuery("SELECT a FROM EvidenceArtifact a JOIN FETCH a.ingestionRun r JOIN FETCH r.sourceEndpoint e JOIN FETCH e.source WHERE a.id IN :ids ORDER BY a.pageNumber",EvidenceArtifact.class)
                    .setParameter("ids",ids).getResultList();
            em.clear();return result;
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var parser=new NationalHighwaysRoadClosuresParser();
        var first=parser.parse(detached.getFirst());var second=parser.parse(detached.get(1));
        assertThat(first.evidenceArtifactId()).isEqualTo(ids.getFirst());assertThat(second.evidenceArtifactId()).isEqualTo(ids.get(1));
        assertThat(first.ingestionRunId()).isEqualTo(second.ingestionRunId());
        assertThat(first.pageNumber()).isEqualTo(1);assertThat(second.pageNumber()).isEqualTo(2);
        assertThat(first.observedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
        assertThat(second.requestedAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        assertThat(first.situations()).isEqualTo(second.situations());
        assertThat(parser.parse(detached.getFirst())).isEqualTo(first);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(detached.getFirst().getPayload()).containsExactly(bytes);
        // No implicit lazy SQL is allowed in interpretation, even for an unrelated detached loading strategy.
        var unloaded=tx.execute(status->{var a=artifacts.findById(ids.getFirst()).orElseThrow();em.clear();return a;});
        assertThatIllegalArgumentException().isThrownBy(()->parser.parse(unloaded)).withMessageContaining("initialized");
        assertThat(snapshot()).isEqualTo(before);
    }
}
