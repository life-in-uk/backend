-- REVIEW DRAFT ONLY. Not under db/migration; never run without separate owner approval.
-- This destructive, one-time conversion removes canonical Guide bodies only after a replacement
-- batch has published. It preserves revision IDs, hashes, authorship, batch/review metadata and
-- V11 publication history; it creates no content archive.
--
-- Review the preflight SELECT result before considering execution. Confirm the application version
-- handles null canonical_form and has a tested redacted-revision path. Execution requires a
-- maintenance window: the transaction takes ACCESS EXCLUSIVE locks. Failure rolls back the trigger
-- change and body updates together. V10's original immutable trigger function is recreated unchanged.

-- Preflight: list bodies eligible for removal. A body must belong to a superseded batch, have a
-- different published replacement for the same Guide, and be unused by all current or pending batches.
SELECT r.id, r.slug, r.revision_number, r.draft_digest, old_batch.id AS superseded_batch_id,
       replacement.id AS replacement_batch_id
FROM guide_revision r
JOIN guide_content_batch old_batch ON old_batch.source_revision_id = r.id AND old_batch.status = 'SUPERSEDED'
JOIN guide_content_batch replacement ON replacement.slug = r.slug
    AND replacement.status = 'PUBLISHED' AND replacement.source_revision_id <> r.id
WHERE r.canonical_form IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM guide_content_batch active
      WHERE active.source_revision_id = r.id AND active.status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED'))
  AND NOT EXISTS (SELECT 1 FROM guide_locale_current current_locale
      WHERE current_locale.batch_id = old_batch.id);

-- Execution section. Move only this section into an explicitly approved, versioned migration/runbook.
BEGIN;
LOCK TABLE guide_revision, guide_content_batch, guide_locale_current IN ACCESS EXCLUSIVE MODE;

ALTER TABLE guide_revision ALTER COLUMN canonical_form DROP NOT NULL;
ALTER TABLE guide_revision ADD COLUMN body_redacted_at timestamptz;
ALTER TABLE guide_revision ADD CONSTRAINT guide_revision_body_redaction_pair
    CHECK ((canonical_form IS NOT NULL AND body_redacted_at IS NULL)
        OR (canonical_form IS NULL AND body_redacted_at IS NOT NULL));

-- The immutable trigger is removed only within this locked transaction. Recreate its existing
-- function unchanged before commit; it continues to reject every UPDATE and DELETE afterwards.
DROP TRIGGER guide_revision_history ON guide_revision;

UPDATE guide_revision r
SET canonical_form = NULL, body_redacted_at = clock_timestamp()
WHERE r.canonical_form IS NOT NULL
  AND EXISTS (SELECT 1 FROM guide_content_batch old_batch
      JOIN guide_content_batch replacement ON replacement.slug = old_batch.slug
          AND replacement.status = 'PUBLISHED' AND replacement.source_revision_id <> old_batch.source_revision_id
      WHERE old_batch.source_revision_id = r.id AND old_batch.status = 'SUPERSEDED'
        AND NOT EXISTS (SELECT 1 FROM guide_locale_current current_locale WHERE current_locale.batch_id = old_batch.id))
  AND NOT EXISTS (SELECT 1 FROM guide_content_batch active
      WHERE active.source_revision_id = r.id AND active.status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED'));

-- Allow a future identical draft to receive a new live V10 revision after the earlier body's removal.
-- Digest uniqueness remains enforced for every revision that still has a body.
ALTER TABLE guide_revision DROP CONSTRAINT guide_revision_slug_draft_digest_key;
CREATE UNIQUE INDEX guide_revision_live_digest ON guide_revision(slug, draft_digest)
    WHERE canonical_form IS NOT NULL;

CREATE TRIGGER guide_revision_history BEFORE INSERT OR UPDATE OR DELETE ON guide_revision
    FOR EACH ROW EXECUTE FUNCTION protect_guide_revision();
COMMIT;
