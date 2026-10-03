package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualificationStatus;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow evidence-owned read boundary: one identity, returned only with successful canonical TfL Underground provenance. */
@Component
public class UndergroundEvidence {
    private final EntityManager entityManager;

    UndergroundEvidence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EvidenceArtifact> successful(UUID evidenceArtifactId) {
        Objects.requireNonNull(evidenceArtifactId, "Evidence identity is required");
        var selected = entityManager.createQuery("""
                select a from EvidenceArtifact a
                join a.ingestionRun r join r.sourceEndpoint e join e.source s
                where a.id = :id and r.status = :success
                  and s.key = :sourceKey and s.scope = :scope and s.enabled = true
                  and e.key = :endpointKey and e.url = :url and e.enabled = true
                  and e.qualificationStatus = :qualified
                """, EvidenceArtifact.class)
                .setParameter("id", evidenceArtifactId)
                .setParameter("success", RunStatus.SUCCESS)
                .setParameter("sourceKey", Source.TFL_KEY)
                .setParameter("scope", Source.TFL_UNDERGROUND_SCOPE)
                .setParameter("endpointKey", SourceEndpoint.TFL_UNDERGROUND_KEY)
                .setParameter("url", SourceEndpoint.TFL_UNDERGROUND_URL)
                .setParameter("qualified", QualificationStatus.QUALIFIED)
                .getResultStream().findFirst();
        // The source-owned permission check also enforces the complete canonical TfL identity.
        selected = selected.filter(artifact -> artifact.getIngestionRun()
                .getSourceEndpoint().isQualifiedAndEnabled());
        selected.ifPresent(entityManager::detach);
        return selected;
    }
}
