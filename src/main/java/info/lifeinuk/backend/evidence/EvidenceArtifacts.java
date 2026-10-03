package info.lifeinuk.backend.evidence;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Insert/read only: persist deliberately avoids Spring Data save's merge behaviour. */
@Repository
class EvidenceArtifacts {
    private final EntityManager entityManager;

    EvidenceArtifacts(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public void append(EvidenceArtifact artifact) {
        entityManager.persist(artifact);
        entityManager.flush();
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceArtifact> findById(UUID id) {
        return Optional.ofNullable(entityManager.find(EvidenceArtifact.class, id));
    }
}
