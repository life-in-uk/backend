-- Applied by export-approved-seed.sh to a disposable copy of the local public
-- schema (never to the local or production database). Leaves only approved,
-- public application data for the first production deployment:
--
-- Kept: every published and superseded (approved) Guide batch with its locale
-- revisions, current locale pointers and publications (open and ended); the
-- Guide revisions they reference and the revisions those were based on; the
-- public Guide projections (guide, guide_source, guide_evidence,
-- guide_evidence_support); workflow activation and fence; source and
-- endpoint configuration with the owner's qualification records; the Bank
-- Holidays ingestion runs and evidence that /api/bank-holidays is served from;
-- the Flyway history.
--
-- Removed: unapproved editorial work (DRAFT/READY batches and their locale
-- revisions, and Guide revisions used only by them); Underground and Roads
-- current state and the TfL and National Highways ingestion evidence behind it
-- (provider data that production produces itself once refresh is approved;
-- copying it would serve stale "current" travel status).
--
-- The history triggers that make these tables append-only are disabled only
-- inside this transaction, only on this copy; foreign keys stay enforced.
\set ON_ERROR_STOP on
BEGIN;

ALTER TABLE evidence_artifact DISABLE TRIGGER evidence_artifact_history;
ALTER TABLE ingestion_run DISABLE TRIGGER ingestion_run_history;
ALTER TABLE guide_revision DISABLE TRIGGER guide_revision_history;

DELETE FROM underground_current_status;
DELETE FROM underground_current_line;
DELETE FROM underground_current_snapshot;
DELETE FROM roads_current_snapshot;

CREATE TEMP TABLE kept_endpoint ON COMMIT DROP AS
    SELECT id FROM source_endpoint WHERE endpoint_key = 'gov-uk-bank-holidays-json';
DO $$ BEGIN
    IF (SELECT count(*) FROM kept_endpoint) <> 1 THEN
        RAISE EXCEPTION 'expected exactly one Bank Holidays endpoint';
    END IF;
END $$;
DELETE FROM evidence_artifact a USING ingestion_run r
    WHERE a.ingestion_run_id = r.id AND r.source_endpoint_id NOT IN (SELECT id FROM kept_endpoint);
DELETE FROM ingestion_run WHERE source_endpoint_id NOT IN (SELECT id FROM kept_endpoint);

DELETE FROM guide_locale_revision
    WHERE batch_id IN (SELECT id FROM guide_content_batch WHERE approved_at IS NULL);
DELETE FROM guide_content_batch WHERE approved_at IS NULL;

WITH RECURSIVE kept(id) AS (
        SELECT revision_id FROM guide_publication
        UNION SELECT source_revision_id FROM guide_content_batch
        UNION SELECT revision_id FROM guide_revision_body_redaction
        UNION SELECT r.based_on_revision_id FROM guide_revision r JOIN kept k ON r.id = k.id
            WHERE r.based_on_revision_id IS NOT NULL)
DELETE FROM guide_revision WHERE id NOT IN (SELECT id FROM kept WHERE id IS NOT NULL);

ALTER TABLE guide_revision ENABLE TRIGGER guide_revision_history;
ALTER TABLE ingestion_run ENABLE TRIGGER ingestion_run_history;
ALTER TABLE evidence_artifact ENABLE TRIGGER evidence_artifact_history;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM guide_content_batch WHERE approved_at IS NULL) THEN
        RAISE EXCEPTION 'unapproved batches remain';
    END IF;
    IF EXISTS (SELECT 1 FROM guide_locale_revision WHERE review_status <> 'PASSED' OR validation_status <> 'VALID') THEN
        RAISE EXCEPTION 'an approved batch has a locale revision that is not valid and reviewed';
    END IF;
    IF EXISTS (SELECT 1 FROM guide_locale_current c JOIN guide_content_batch b ON b.id = c.batch_id WHERE b.status <> 'PUBLISHED') THEN
        RAISE EXCEPTION 'a current locale points at a batch that is not published';
    END IF;
    IF EXISTS (SELECT 1 FROM guide g WHERE g.status = 'PUBLISHED'
               AND NOT EXISTS (SELECT 1 FROM guide_publication p WHERE p.slug = g.slug AND p.ended_at IS NULL)) THEN
        RAISE EXCEPTION 'a published Guide has no open publication';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND NOT t.tgisinternal AND t.tgenabled = 'D') THEN
        RAISE EXCEPTION 'a trigger was left disabled';
    END IF;
END $$;

COMMIT;
