package info.lifeinuk.backend.evidence;

import info.lifeinuk.backend.source.QualificationStatus;
import info.lifeinuk.backend.source.Source;
import info.lifeinuk.backend.source.SourceEndpoint;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow evidence-owned read boundary; callers cannot choose a source or observation. */
@Component
public class BankHolidaysEvidence {
    private final EntityManager entityManager;

    BankHolidaysEvidence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EvidenceArtifact> latestSuccessful() {
        var selected = entityManager.createQuery("""
                select a from EvidenceArtifact a
                join a.ingestionRun r join r.sourceEndpoint e join e.source s
                where r.status = :success
                  and s.key = :sourceKey and s.scope = :scope and s.enabled = true
                  and e.key = :endpointKey and e.url = :url and e.enabled = true
                  and e.qualificationStatus = :qualified
                  and e.qualificationRecord is not null and trim(e.qualificationRecord) <> ''
                order by r.completedAt desc, r.id desc, a.observedAt desc, a.id desc
                """, EvidenceArtifact.class)
                .setParameter("success", RunStatus.SUCCESS)
                .setParameter("sourceKey", Source.BANK_HOLIDAYS_KEY)
                .setParameter("scope", Source.BANK_HOLIDAYS_SCOPE)
                .setParameter("endpointKey", SourceEndpoint.BANK_HOLIDAYS_KEY)
                .setParameter("url", SourceEndpoint.BANK_HOLIDAYS_URL)
                .setParameter("qualified", QualificationStatus.QUALIFIED)
                .setMaxResults(1).getResultStream().findFirst();
        // Reuse the source-owned domain permission check as well as SQL predicates:
        // Java blank-string semantics are deliberately stricter than PostgreSQL trim.
        selected = selected.filter(artifact -> artifact.getIngestionRun()
                .getSourceEndpoint().isQualifiedAndEnabled());
        selected.ifPresent(entityManager::detach);
        return selected;
    }
}
