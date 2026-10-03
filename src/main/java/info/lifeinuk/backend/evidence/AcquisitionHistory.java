package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualifiedSourceEndpoints;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short evidence-owned transactions; never performs external I/O. */
@Component
public class AcquisitionHistory {
    private final QualifiedSourceEndpoints endpoints;
    private final IngestionRunRepository runs;
    private final EvidenceArtifacts artifacts;
    private final EntityManager entityManager;

    AcquisitionHistory(QualifiedSourceEndpoints endpoints, IngestionRunRepository runs,
            EvidenceArtifacts artifacts, EntityManager entityManager) {
        this.endpoints = endpoints;
        this.runs = runs;
        this.artifacts = artifacts;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID start(UUID endpointId) {
        IngestionRun run = runs.save(IngestionRun.start(endpoints.requireQualified(endpointId), Instant.now()));
        entityManager.flush();
        return run.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID succeed(UUID runId, byte[] payload, String mediaType, Instant observedAt) {
        IngestionRun run = requireRun(runId);
        EvidenceArtifact artifact = new EvidenceArtifact(run, payload, mediaType, observedAt);
        artifacts.append(artifact);
        run.succeed(Instant.now());
        entityManager.flush();
        return artifact.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, String code, String message) {
        requireRun(runId).fail(Instant.now(), code, message);
        entityManager.flush();
    }

    private IngestionRun requireRun(UUID id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Ingestion run does not exist"));
    }
}
