package info.lifeinuk.backend.guides;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class GuideContentBatchStore {
    private final JdbcTemplate jdbc;

    GuideContentBatchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockSlug(String slug) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                "guide-content-batch:" + slug);
    }

    void lockIdempotencyKey(String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                "guide-content-idempotency:" + key);
    }

    Optional<Batch> byIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM guide_content_batch WHERE idempotency_key = ?", GuideContentBatchStore::batch, key)
                .stream().findFirst();
    }

    Optional<Batch> byId(UUID id) {
        return jdbc.query("SELECT * FROM guide_content_batch WHERE id = ?", GuideContentBatchStore::batch, id)
                .stream().findFirst();
    }

    Batch insertBatch(UUID id, String slug, UUID sourceRevisionId, String sourceDigest, String idempotencyKey,
            String requestDigest, String policyVersion, String sourceVersion, Instant validFrom, Instant validUntil, String createdBy) {
        jdbc.update("""
                INSERT INTO guide_content_batch(id, slug, source_revision_id, source_digest, idempotency_key,
                    request_digest, policy_version, source_version, status, valid_from, valid_until, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, id, slug, sourceRevisionId, sourceDigest, idempotencyKey, requestDigest, policyVersion,
                sourceVersion, Timestamp.from(validFrom), validUntil == null ? null : Timestamp.from(validUntil), createdBy);
        return byId(id).orElseThrow();
    }

    List<LocaleRevision> locales(UUID batchId) {
        return jdbc.query("SELECT * FROM guide_locale_revision WHERE batch_id = ? ORDER BY locale",
                GuideContentBatchStore::localeRevision, batchId);
    }

    Optional<LocaleRevision> locale(UUID batchId, String locale) {
        return jdbc.query("SELECT * FROM guide_locale_revision WHERE batch_id = ? AND locale = ?",
                GuideContentBatchStore::localeRevision, batchId, locale).stream().findFirst();
    }

    LocaleRevision addLocale(UUID revisionId, UUID batchId, String locale, UUID sourceRevisionId,
            String canonicalForm, String hash) {
        jdbc.update("""
                INSERT INTO guide_locale_revision(id, batch_id, locale, source_revision_id, canonical_form,
                    content_hash, validation_status, review_status)
                VALUES (?, ?, ?, ?, ?, ?, 'VALID', 'PENDING')
                """, revisionId, batchId, locale, sourceRevisionId, canonicalForm, hash);
        refreshStatus(batchId);
        return locale(batchId, locale).orElseThrow();
    }

    LocaleRevision replaceLocale(UUID revisionId, UUID batchId, String locale, UUID sourceRevisionId,
            String canonicalForm, String hash) {
        Optional<LocaleRevision> existing = locale(batchId, locale);
        if (existing.isPresent() && "VALID".equals(existing.get().validationStatus())
                && hash.equals(existing.get().contentHash())) { return existing.get(); }
        jdbc.update("DELETE FROM guide_locale_revision WHERE batch_id = ? AND locale = ?", batchId, locale);
        return addLocale(revisionId, batchId, locale, sourceRevisionId, canonicalForm, hash);
    }

    void requireOpenDraft(Batch batch) {
        if (!Set.of("DRAFT", "READY").contains(batch.status())) {
            throw new IllegalStateException("Batch no longer accepts locale draft changes");
        }
        if (batch.validUntil() != null && !batch.validUntil().isAfter(Instant.now())) {
            jdbc.update("UPDATE guide_content_batch SET status = 'EXPIRED' WHERE id = ?", batch.id());
            throw new IllegalStateException("Batch validity has expired");
        }
    }

    LocaleRevision failLocale(UUID revisionId, UUID batchId, String locale, UUID sourceRevisionId, String code) {
        jdbc.update("""
                INSERT INTO guide_locale_revision(id, batch_id, locale, source_revision_id, canonical_form,
                    content_hash, validation_status, failure_code, review_status)
                VALUES (?, ?, ?, ?, NULL, NULL, 'FAILED', ?, 'PENDING')
                ON CONFLICT (batch_id, locale) DO UPDATE SET id = EXCLUDED.id,
                    canonical_form = NULL, content_hash = NULL, validation_status = 'FAILED',
                    failure_code = EXCLUDED.failure_code, review_status = 'PENDING', reviewed_hash = NULL,
                    review_result = NULL, reviewer = NULL, reviewed_at = NULL, updated_at = clock_timestamp()
                """, revisionId, batchId, locale, sourceRevisionId, code);
        refreshStatus(batchId);
        return locale(batchId, locale).orElseThrow();
    }

    LocaleRevision review(UUID batchId, String locale, String hash, String result, String reviewer, boolean passed) {
        int updated = jdbc.update("""
                UPDATE guide_locale_revision SET review_status = ?, reviewed_hash = ?, review_result = ?::jsonb,
                    reviewer = ?, reviewed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE batch_id = ? AND locale = ? AND validation_status = 'VALID' AND content_hash = ?
                """, passed ? "PASSED" : "FAILED", hash, result, reviewer, batchId, locale, hash);
        if (updated != 1) { throw new StaleLocaleRevision("The locale revision or content hash is stale"); }
        refreshStatus(batchId);
        return locale(batchId, locale).orElseThrow();
    }

    void approve(UUID batchId, String actor) {
        int updated = jdbc.update("""
                UPDATE guide_content_batch SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = ?
                WHERE id = ? AND status = 'READY'
                    AND (SELECT count(*) FROM guide_locale_revision WHERE batch_id = ? AND validation_status = 'VALID'
                         AND review_status = 'PASSED' AND reviewed_hash = content_hash) = 5
                """, actor, batchId, batchId);
        if (updated != 1) { throw new IncompleteBatch("All five current locale revisions must pass validation and hash-specific review"); }
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    void expire(UUID batchId) {
        jdbc.update("UPDATE guide_content_batch SET status = 'EXPIRED' WHERE id = ? AND status IN ('DRAFT','READY','APPROVED','PUBLISHED')", batchId);
    }

    List<CurrentLocale> currentLocales(String slug) {
        return jdbc.query("""
                SELECT c.locale, c.batch_id, c.locale_revision_id, c.valid_from, c.valid_until
                FROM guide_locale_current c WHERE c.slug = ? ORDER BY c.locale
                """, (row, index) -> new CurrentLocale(row.getString("locale"), row.getObject("batch_id", UUID.class),
                row.getObject("locale_revision_id", UUID.class), instant(row, "valid_from"), nullableInstant(row, "valid_until")), slug);
    }

    void publish(UUID batchId) {
        Batch batch = byId(batchId).orElseThrow(() -> new IllegalArgumentException("Batch not found"));
        var entries = locales(batchId);
        if (!fiveReviewed(entries)) { throw new IncompleteBatch("Batch is incomplete or has stale reviews"); }
        jdbc.update("""
                INSERT INTO guide_locale_current(slug, locale, batch_id, locale_revision_id, valid_from, valid_until)
                SELECT ?, locale, batch_id, id, ?, ? FROM guide_locale_revision WHERE batch_id = ?
                ON CONFLICT (slug, locale) DO UPDATE SET batch_id = EXCLUDED.batch_id,
                    locale_revision_id = EXCLUDED.locale_revision_id, valid_from = EXCLUDED.valid_from,
                    valid_until = EXCLUDED.valid_until
                """, batch.slug(), Timestamp.from(batch.validFrom()), batch.validUntil() == null ? null : Timestamp.from(batch.validUntil()), batchId);
        jdbc.update("""
                UPDATE guide_locale_revision old SET canonical_form = NULL, validation_status = 'SUPERSEDED',
                    updated_at = clock_timestamp()
                WHERE old.batch_id IN (SELECT b.id FROM guide_content_batch b
                    WHERE b.slug = ? AND b.status = 'PUBLISHED' AND b.id <> ?)
                  AND old.canonical_form IS NOT NULL
                """, batch.slug(), batchId);
        jdbc.update("UPDATE guide_content_batch SET status = 'SUPERSEDED' WHERE slug = ? AND id <> ? AND status = 'PUBLISHED'",
                batch.slug(), batchId);
        jdbc.update("UPDATE guide_content_batch SET status = 'PUBLISHED', published_at = clock_timestamp() WHERE id = ?", batchId);
    }

    Optional<LocalizedCurrent> current(String slug, String locale) {
        return jdbc.query("""
                SELECT b.id AS batch_id, b.source_revision_id, b.published_at,
                    r.id AS revision_id, r.canonical_form, r.content_hash
                FROM guide_locale_current c
                JOIN guide_content_batch b ON b.id = c.batch_id
                JOIN guide_locale_revision r ON r.id = c.locale_revision_id AND r.batch_id = c.batch_id
                WHERE c.slug = ? AND c.locale = ? AND c.valid_from <= CURRENT_TIMESTAMP
                    AND (c.valid_until IS NULL OR c.valid_until > CURRENT_TIMESTAMP)
                    AND b.status = 'PUBLISHED'
                """, (row, index) -> new LocalizedCurrent(row.getObject("batch_id", UUID.class),
                row.getObject("source_revision_id", UUID.class), row.getObject("revision_id", UUID.class),
                row.getString("canonical_form"), row.getString("content_hash"),
                nullableInstant(row, "published_at")), slug, locale).stream().findFirst();
    }

    record Batch(UUID id, String slug, UUID sourceRevisionId, String sourceDigest, String idempotencyKey,
            String requestDigest, String policyVersion, String sourceVersion, String status,
            Instant validFrom, Instant validUntil, String createdBy, Instant approvedAt, String approvedBy) { }
    record LocaleRevision(UUID id, UUID batchId, String locale, UUID sourceRevisionId, String canonicalForm,
            String contentHash, String validationStatus, String failureCode, String reviewStatus,
            String reviewedHash, String reviewResult, String reviewer, Instant reviewedAt) { }
    record CurrentLocale(String locale, UUID batchId, UUID revisionId, Instant validFrom, Instant validUntil) { }
    record LocalizedCurrent(UUID batchId, UUID sourceRevisionId, UUID revisionId, String canonicalForm,
            String contentHash, Instant publishedAt) { }

    static class IncompleteBatch extends org.springframework.dao.DataAccessException {
        IncompleteBatch(String message) { super(message); }
    }
    static class StaleLocaleRevision extends org.springframework.dao.DataAccessException {
        StaleLocaleRevision(String message) { super(message); }
    }

    private void refreshStatus(UUID batchId) {
        jdbc.update("""
                UPDATE guide_content_batch b SET status = CASE WHEN
                    (SELECT count(*) FROM guide_locale_revision r WHERE r.batch_id = b.id) = 5
                    AND (SELECT count(*) FROM guide_locale_revision r WHERE r.batch_id = b.id
                         AND r.validation_status = 'VALID' AND r.review_status = 'PASSED'
                         AND r.reviewed_hash = r.content_hash) = 5 THEN 'READY' ELSE 'DRAFT' END
                WHERE b.id = ? AND b.status IN ('DRAFT', 'READY')
                """, batchId);
    }

    private static boolean fiveReviewed(List<LocaleRevision> entries) {
        return entries.size() == 5 && entries.stream().allMatch(r -> "VALID".equals(r.validationStatus())
                && "PASSED".equals(r.reviewStatus()) && r.contentHash().equals(r.reviewedHash()));
    }

    private static Batch batch(ResultSet row, int index) throws SQLException {
        return new Batch(row.getObject("id", UUID.class), row.getString("slug"), row.getObject("source_revision_id", UUID.class),
                row.getString("source_digest"), row.getString("idempotency_key"), row.getString("request_digest"),
                row.getString("policy_version"), row.getString("source_version"), row.getString("status"),
                instant(row, "valid_from"), nullableInstant(row, "valid_until"), row.getString("created_by"),
                nullableInstant(row, "approved_at"), row.getString("approved_by"));
    }
    private static LocaleRevision localeRevision(ResultSet row, int index) throws SQLException {
        return new LocaleRevision(row.getObject("id", UUID.class), row.getObject("batch_id", UUID.class), row.getString("locale"),
                row.getObject("source_revision_id", UUID.class), row.getString("canonical_form"), row.getString("content_hash"),
                row.getString("validation_status"), row.getString("failure_code"), row.getString("review_status"),
                row.getString("reviewed_hash"), row.getString("review_result"), row.getString("reviewer"), nullableInstant(row, "reviewed_at"));
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, java.time.OffsetDateTime.class).toInstant();
    }
    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
