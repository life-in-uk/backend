package info.lifeinuk.backend.guides;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only revision persistence. Writes must run inside the caller's transaction and slug lock. */
@Repository
class GuideRevisionStore {
    private static final String COLUMNS = """
            id, slug, revision_number, canonicalization, canonical_form, draft_digest, based_on_revision_id,
            created_at, created_by, db_session_user, ai_assisted, note
            """;
    private final JdbcTemplate jdbc;

    GuideRevisionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Serializes revision creation per slug until the surrounding transaction ends. */
    void lockSlug(String slug) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, "guide-revision:" + slug);
    }

    Optional<GuideRevision> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM guide_revision WHERE id = ?", GuideRevisionStore::revision, id)
                .stream().findFirst();
    }

    Optional<GuideRevision> findByDigest(String slug, String draftDigest) {
        return jdbc.query("SELECT " + COLUMNS + " FROM guide_revision WHERE slug = ? AND draft_digest = ?",
                GuideRevisionStore::revision, slug, draftDigest).stream().findFirst();
    }

    int nextRevisionNumber(String slug) {
        return jdbc.queryForObject("SELECT coalesce(max(revision_number), 0) + 1 FROM guide_revision WHERE slug = ?",
                Integer.class, slug);
    }

    /** created_at and db_session_user are set by the database trigger. */
    void insert(UUID id, String slug, int revisionNumber, String canonicalForm, String draftDigest,
            UUID basedOnRevisionId, String createdBy, boolean aiAssisted, String note) {
        jdbc.update("""
                INSERT INTO guide_revision (id, slug, revision_number, canonicalization, canonical_form, draft_digest,
                    based_on_revision_id, created_by, ai_assisted, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, slug, revisionNumber, GuideContentDigest.VERSION, canonicalForm, draftDigest,
                basedOnRevisionId, createdBy, aiAssisted, note);
    }

    /** The current public projection row for a slug, if any. Read-only. */
    Optional<PublicState> publicState(String slug) {
        return jdbc.query("SELECT status, updated_at FROM guide WHERE slug = ?", (row, index) ->
                new PublicState(GuideStatus.valueOf(row.getString("status")),
                        row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()), slug)
                .stream().findFirst();
    }

    record PublicState(GuideStatus status, Instant updatedAt) { }

    private static GuideRevision revision(ResultSet row, int index) throws SQLException {
        return new GuideRevision(row.getObject("id", UUID.class), row.getString("slug"), row.getInt("revision_number"),
                row.getString("canonicalization"), row.getString("canonical_form"), row.getString("draft_digest"),
                row.getObject("based_on_revision_id", UUID.class),
                row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(), row.getString("created_by"),
                row.getString("db_session_user"), row.getBoolean("ai_assisted"), row.getString("note"));
    }
}
