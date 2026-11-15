package info.lifeinuk.backend.guides;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Publication history persistence. Writes must run inside the publication service's transaction and slug lock. */
@Repository
class GuidePublicationStore {
    record Publication(UUID id, String slug, UUID revisionId, Instant publishedAt, String publishedDigest,
            String publishedBy, String dbSessionUser, String checklistId, LocalDate verifiedOn, boolean legacy,
            Instant endedAt, String endReason, String endedBy, String endNote) {
        boolean open() { return endedAt == null; }
    }

    private static final String COLUMNS = """
            id, slug, revision_id, published_at, published_digest, published_by, db_session_user, checklist_id,
            verified_on, legacy, ended_at, end_reason, ended_by, end_note
            """;
    private final JdbcTemplate jdbc;

    GuidePublicationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Serializes publication and withdrawal per slug until the surrounding transaction ends. */
    void lockSlug(String slug) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, "guide-publication:" + slug);
    }

    boolean activated() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM guide_workflow_activation)", Boolean.class));
    }

    /** Marks this transaction as a workflow projection write, for the activation milestone's write guard. */
    void markProjectionWrite() {
        jdbc.queryForObject("SELECT set_config('life_in_uk.guide_publication', 'on', true)", String.class);
    }

    Optional<Publication> open(String slug) {
        return jdbc.query("SELECT " + COLUMNS + " FROM guide_publication WHERE slug = ? AND ended_at IS NULL",
                GuidePublicationStore::publication, slug).stream().findFirst();
    }

    Optional<Publication> forRevision(UUID revisionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM guide_publication WHERE revision_id = ?",
                GuidePublicationStore::publication, revisionId).stream().findFirst();
    }

    List<Publication> history(String slug) {
        return jdbc.query("SELECT " + COLUMNS + " FROM guide_publication WHERE slug = ? ORDER BY published_at, id",
                GuidePublicationStore::publication, slug);
    }

    boolean projectionExists(String slug) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM guide WHERE slug = ?)", Boolean.class, slug));
    }

    void insert(UUID id, String slug, UUID revisionId, Instant publishedAt, String publishedDigest, String publishedBy,
            String checklistId, LocalDate verifiedOn) {
        jdbc.update("""
                INSERT INTO guide_publication (id, slug, revision_id, published_at, published_digest, published_by,
                    checklist_id, verified_on, legacy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)
                """, id, slug, revisionId, Timestamp.from(publishedAt), publishedDigest, publishedBy, checklistId, verifiedOn);
    }

    void end(UUID id, Instant endedAt, String reason, String endedBy, String note) {
        int updated = jdbc.update("""
                UPDATE guide_publication SET ended_at = ?, end_reason = ?, ended_by = ?, end_note = ?
                WHERE id = ? AND ended_at IS NULL
                """, Timestamp.from(endedAt), reason, endedBy, note, id);
        if (updated != 1) { throw new IllegalStateException("Publication is no longer open: " + id); }
    }

    /** Removes the public projection rows of one slug, children first. */
    void deleteProjection(String slug) {
        jdbc.update("DELETE FROM guide_evidence_support WHERE guide_id IN (SELECT id FROM guide WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM guide_evidence WHERE guide_id IN (SELECT id FROM guide WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM guide_source WHERE guide_id IN (SELECT id FROM guide WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM guide WHERE slug = ?", slug);
    }

    private static Publication publication(ResultSet row, int index) throws SQLException {
        var ended = row.getObject("ended_at", OffsetDateTime.class);
        return new Publication(row.getObject("id", UUID.class), row.getString("slug"), row.getObject("revision_id", UUID.class),
                row.getObject("published_at", OffsetDateTime.class).toInstant(), row.getString("published_digest"),
                row.getString("published_by"), row.getString("db_session_user"), row.getString("checklist_id"),
                row.getObject("verified_on", LocalDate.class), row.getBoolean("legacy"),
                ended == null ? null : ended.toInstant(), row.getString("end_reason"), row.getString("ended_by"),
                row.getString("end_note"));
    }
}
