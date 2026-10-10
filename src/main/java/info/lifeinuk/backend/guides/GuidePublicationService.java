package info.lifeinuk.backend.guides;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit, transactional publication and withdrawal of immutable revisions (architecture sections 3, 6 and 7).
 * Each operation locks the slug, re-validates everything it relies on, writes the public projection and the
 * publication history in one transaction, and refuses while the workflow is not activated.
 */
@Service
class GuidePublicationService {
    enum Outcome { PUBLISHED, ALREADY_PUBLISHED, WITHDRAWN }
    record Result(Outcome outcome, GuidePublicationStore.Publication publication) { }

    /** An operator's explicit publication request; every field is required. {@code expectCurrent} null means none. */
    record PublishRequest(UUID revisionId, String expectDigest, UUID expectCurrent, String checklistId,
            LocalDate verifiedOn, boolean confirmed, String operator) { }

    /** A state-based refusal. The message starts with a stable code. */
    static final class Refused extends IllegalStateException {
        final String code;
        Refused(String code, String message) { super(code + ": " + message); this.code = code; }
    }

    private final GuidePublicationStore publications;
    private final GuideRevisionService revisions;
    private final GuideQualityGate gate;
    private final GuideImporter importer;
    private final Clock clock;

    @Autowired
    GuidePublicationService(GuidePublicationStore publications, GuideRevisionService revisions, GuideQualityGate gate,
            GuideImporter importer) {
        this(publications, revisions, gate, importer, Clock.systemUTC());
    }

    // Deterministic seam for publication time.
    GuidePublicationService(GuidePublicationStore publications, GuideRevisionService revisions, GuideQualityGate gate,
            GuideImporter importer, Clock clock) {
        this.publications = publications;
        this.revisions = revisions;
        this.gate = gate;
        this.importer = importer;
        this.clock = clock;
    }

    @Transactional
    public Result publish(PublishRequest request) {
        Objects.requireNonNull(request, "Publication request is required");
        String operator = GuideRevisionService.operator(request.operator());
        if (!request.confirmed()) {
            throw new IllegalArgumentException("Publication requires explicit operator confirmation (--confirm)");
        }
        if (request.expectDigest() == null || !request.expectDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("--expect-digest must be the revision's 64-character lower-case draft digest");
        }
        if (request.checklistId() == null || request.verifiedOn() == null || request.revisionId() == null) {
            throw new IllegalArgumentException("Publication requires a revision, a checklist and a verified-on date");
        }
        var revision = revisions.find(request.revisionId())
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + request.revisionId()));

        publications.lockSlug(revision.slug());
        if (!publications.activated()) {
            throw new Refused("NOT_ACTIVATED", "the publication workflow is not activated for this database");
        }
        if (!request.expectDigest().equals(revision.draftDigest())) {
            throw new Refused("STALE_DIGEST", "expected " + request.expectDigest() + " but revision has " + revision.draftDigest());
        }
        var current = publications.open(revision.slug());
        var previous = publications.forRevision(revision.id());
        if (previous.isPresent()) {
            if (previous.get().open()) { return new Result(Outcome.ALREADY_PUBLISHED, previous.get()); }
            throw new Refused("ALREADY_USED", "revision " + revision.id() + " was published before and ended "
                    + previous.get().endReason() + "; create a new revision instead");
        }
        UUID currentId = current.map(GuidePublicationStore.Publication::id).orElse(null);
        if (!Objects.equals(request.expectCurrent(), currentId)) {
            throw new Refused("STALE_CURRENT", "expected current publication " + label(request.expectCurrent())
                    + " but it is " + label(currentId));
        }
        boolean projection = publications.projectionExists(revision.slug());
        if (current.isEmpty() && projection) {
            throw new Refused("UNMANAGED_PUBLIC_GUIDE", "slug " + revision.slug()
                    + " has public content without publication history; it must be baselined first");
        }
        if (current.isPresent() && !projection) {
            throw new Refused("INCONSISTENT", "open publication " + currentId + " has no public projection");
        }

        // Re-validate the stored content inside the transaction: exact canonical form, digest and blocking checks.
        Instant now = clock.instant();
        var report = gate.check(revision, revisions.publicState(revision.slug()), now);
        if (report.blocked()) {
            throw new Refused("QUALITY_GATE", report.of(GuideQualityGate.Severity.BLOCKING).stream()
                    .map(GuideQualityGate.Finding::code).distinct().toList().toString());
        }
        var draft = report.definition();
        if (!GuideContentDigest.canonicalForm(draft).equals(revision.canonicalForm())
                || !GuideContentDigest.digest(draft).equals(revision.draftDigest())) {
            throw new Refused("CANONICAL_MISMATCH", "stored text is not the exact guide-content-v1 form of its digest");
        }
        if (!report.checklist().id.equals(request.checklistId())) {
            throw new IllegalArgumentException("Category " + draft.category() + " requires checklist "
                    + report.checklist().id + ", not " + request.checklistId());
        }
        Instant publishedAt = now.truncatedTo(ChronoUnit.SECONDS);
        if (request.verifiedOn().isAfter(LocalDate.ofInstant(publishedAt, ZoneOffset.UTC))) {
            throw new IllegalArgumentException("--verified-on cannot be in the future: " + request.verifiedOn());
        }
        if (publishedAt.isBefore(draft.updatedAt())) {
            throw new Refused("UPDATED_AT_IN_FUTURE", "revision updatedAt " + draft.updatedAt() + " is after the publication time");
        }

        // derive(D_r, P): identical except status PUBLISHED and publishedAt = updatedAt = P (Guide.publish semantics).
        var published = new GuideImportDefinition(draft.slug(), draft.category(), draft.title(), draft.summary(),
                draft.content(), GuideStatus.PUBLISHED, publishedAt, publishedAt, draft.sources(), draft.evidence());
        String publishedDigest = GuideContentDigest.digest(published);

        publications.markProjectionWrite();
        importer.apply(published);
        current.ifPresent(open -> publications.end(open.id(), publishedAt, "REPLACED", operator, null));
        UUID id = UUID.randomUUID();
        publications.insert(id, draft.slug(), revision.id(), publishedAt, publishedDigest, operator,
                request.checklistId(), request.verifiedOn());
        return new Result(Outcome.PUBLISHED, publications.forRevision(revision.id()).orElseThrow());
    }

    @Transactional
    public Result withdraw(String slug, UUID expectCurrent, String reason, String operator) {
        String endedBy = GuideRevisionService.operator(operator);
        Guide.key(slug, 160);
        if (expectCurrent == null) { throw new IllegalArgumentException("--expect-current must name the open publication"); }
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("--reason must be nonblank text of at most 2000 characters");
        }
        publications.lockSlug(slug);
        if (!publications.activated()) {
            throw new Refused("NOT_ACTIVATED", "the publication workflow is not activated for this database");
        }
        var current = publications.open(slug)
                .orElseThrow(() -> new Refused("NOT_PUBLIC", "slug " + slug + " has no open publication"));
        if (!current.id().equals(expectCurrent)) {
            throw new Refused("STALE_CURRENT", "expected current publication " + expectCurrent + " but it is " + current.id());
        }
        publications.markProjectionWrite();
        publications.deleteProjection(slug);
        Instant endedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (endedAt.isBefore(current.publishedAt())) { endedAt = current.publishedAt(); }
        publications.end(current.id(), endedAt, "WITHDRAWN", endedBy, reason);
        return new Result(Outcome.WITHDRAWN, publications.forRevision(current.revisionId()).orElseThrow());
    }

    private static String label(UUID id) { return id == null ? "none" : id.toString(); }
}
