package info.lifeinuk.backend.guides;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit administrative baseline and activation operations; never run automatically. */
@Service
class GuideActivation {
    record Verification(boolean activated, int publications) { }

    private final JdbcTemplate jdbc;
    private final GuideRepository guides;
    private final GuideRevisionService revisions;
    private final GuidePublicationStore publications;

    GuideActivation(JdbcTemplate jdbc, GuideRepository guides, GuideRevisionService revisions,
            GuidePublicationStore publications) {
        this.jdbc = jdbc;
        this.guides = guides;
        this.revisions = revisions;
        this.publications = publications;
    }

    private void lockProjection() {
        jdbc.execute("LOCK TABLE guide, guide_source, guide_evidence, guide_evidence_support IN SHARE ROW EXCLUSIVE MODE");
    }

    /** Adds immutable revision and legacy ledger metadata only; it never changes the public projection. */
    @Transactional
    public UUID baseline(GuideImportDefinition artifact, String operator) {
        operator = GuideRevisionService.operator(operator);
        lockProjection();
        if (publications.activated()) throw new IllegalStateException("Already activated; baseline is unavailable");
        if (artifact.status() != GuideStatus.PUBLISHED || !Objects.equals(artifact.publishedAt(), artifact.updatedAt())
                || artifact.evidence() == null) {
            throw new IllegalArgumentException("Baseline requires PUBLISHED content, equal publishedAt/updatedAt, and explicit evidence");
        }
        var projection = guides.findBySlug(artifact.slug()).orElseThrow(() -> new IllegalArgumentException("Baseline Guide missing"));
        String digest = GuideContentDigest.digest(artifact);
        if (!digest.equals(GuideContentDigest.digest(GuideSnapshot.from(projection)))) {
            throw new IllegalStateException("Baseline does not match live projection: " + artifact.slug());
        }
        var existing = publications.open(artifact.slug());
        if (existing.isPresent()) {
            if (existing.get().legacy() && existing.get().publishedDigest().equals(digest)) return existing.get().id();
            throw new IllegalStateException("Baseline history mismatch: " + artifact.slug());
        }

        var draft = new GuideImportDefinition(artifact.slug(), artifact.category(), artifact.title(), artifact.summary(),
                artifact.content(), GuideStatus.DRAFT, null, artifact.updatedAt(), artifact.sources(), artifact.evidence());
        var revision = revisions.create(draft, null, operator, false, "Legacy published baseline").revision();
        if (publications.forRevision(revision.id()).isPresent()) throw new IllegalStateException("Baseline revision already used");
        UUID publicationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guide_publication(id, slug, revision_id, published_at, published_digest, published_by,
                    checklist_id, legacy)
                VALUES (?, ?, ?, ?, ?, ?, 'legacy-baseline', true)
                """, publicationId, artifact.slug(), revision.id(), Timestamp.from(artifact.publishedAt()), digest, operator);
        return publicationId;
    }

    @Transactional
    public Verification activate(String operator, boolean confirmed) {
        operator = GuideRevisionService.operator(operator);
        if (!confirmed) throw new IllegalArgumentException("Activation requires explicit --confirm");
        lockProjection();
        if (publications.activated()) throw new IllegalStateException("Already activated");
        var verification = verifyLocked(true);
        jdbc.update("INSERT INTO guide_workflow_activation(activated_by) VALUES (?)", operator);
        return new Verification(true, verification.publications());
    }

    @Transactional
    public Verification verify() {
        lockProjection();
        return verifyLocked(false);
    }

    private Verification verifyLocked(boolean legacyOnly) {
        int count = 0;
        for (var guide : guides.findAll()) {
            if (guide.getStatus() != GuideStatus.PUBLISHED) {
                throw new IllegalStateException("DRAFT projection cannot be activated or verified: " + guide.getSlug());
            }
            var publication = publications.open(guide.getSlug())
                    .orElseThrow(() -> new IllegalStateException("Missing open publication: " + guide.getSlug()));
            if (legacyOnly && !publication.legacy()) {
                throw new IllegalStateException("Activation requires a legacy baseline: " + guide.getSlug());
            }
            var revision = revisions.find(publication.revisionId()).orElseThrow();
            var draft = revisions.definition(revision.canonicalForm());
            if (!GuideContentDigest.canonicalForm(draft).equals(revision.canonicalForm())
                    || !GuideContentDigest.digest(draft).equals(revision.draftDigest())) {
                throw new IllegalStateException("Revision canonical identity mismatch: " + guide.getSlug());
            }
            var expected = new GuideImportDefinition(draft.slug(), draft.category(), draft.title(), draft.summary(),
                    draft.content(), GuideStatus.PUBLISHED, publication.publishedAt(), publication.publishedAt(),
                    draft.sources(), draft.evidence());
            String digest = GuideContentDigest.digest(expected);
            if (!digest.equals(publication.publishedDigest())
                    || !digest.equals(GuideContentDigest.digest(GuideSnapshot.from(guide)))) {
                throw new IllegalStateException("Projection or publication digest mismatch: " + guide.getSlug());
            }
            count++;
        }
        Integer openCount = jdbc.queryForObject("SELECT count(*) FROM guide_publication WHERE ended_at IS NULL", Integer.class);
        if (openCount == null || count != openCount) throw new IllegalStateException("Open publication lacks projection");
        if (legacyOnly) {
            Integer localePointers = jdbc.queryForObject("SELECT count(*) FROM guide_locale_current", Integer.class);
            if (localePointers == null || localePointers != 0) {
                throw new IllegalStateException("Legacy activation requires no pre-existing locale current pointers");
            }
        }
        return new Verification(publications.activated(), count);
    }
}
