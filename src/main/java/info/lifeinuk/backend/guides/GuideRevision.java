package info.lifeinuk.backend.guides;

import java.time.Instant;
import java.util.UUID;

/** One stored, immutable DRAFT content revision. The canonical form is the exact guide-content-v1 text. */
record GuideRevision(UUID id, String slug, int revisionNumber, String canonicalization, String canonicalForm,
        String draftDigest, UUID basedOnRevisionId, Instant createdAt, String createdBy, String dbSessionUser,
        boolean aiAssisted, String note) { }
