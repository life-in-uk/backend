-- Adds an explicit, owner-invoked body-redaction procedure. This migration never redacts content.
-- V10 identity and digest fields remain immutable; only canonical_form may be nulled after replacement.
ALTER TABLE guide_revision ALTER COLUMN canonical_form DROP NOT NULL;
ALTER TABLE guide_revision ADD COLUMN body_redacted_at timestamptz;
ALTER TABLE guide_revision ADD CONSTRAINT guide_revision_body_redaction_pair
    CHECK ((canonical_form IS NOT NULL AND body_redacted_at IS NULL)
        OR (canonical_form IS NULL AND body_redacted_at IS NOT NULL));

ALTER TABLE guide_revision DROP CONSTRAINT guide_revision_slug_draft_digest_key;
CREATE UNIQUE INDEX guide_revision_live_digest ON guide_revision(slug, draft_digest)
    WHERE canonical_form IS NOT NULL;

CREATE TABLE guide_revision_body_redaction (
    revision_id uuid PRIMARY KEY REFERENCES guide_revision(id),
    slug varchar(160) NOT NULL,
    draft_digest char(64) NOT NULL CHECK (draft_digest ~ '^[0-9a-f]{64}$'),
    replaced_publication_id uuid NOT NULL REFERENCES guide_publication(id),
    replacement_publication_id uuid NOT NULL REFERENCES guide_publication(id),
    redacted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    redacted_by varchar(100) NOT NULL CHECK (btrim(redacted_by) <> ''),
    reason varchar(2000) NOT NULL CHECK (btrim(reason) <> '')
);

CREATE FUNCTION protect_guide_revision_body_redaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Guide body-redaction audit is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER guide_revision_body_redaction_history
    BEFORE UPDATE OR DELETE ON guide_revision_body_redaction
    FOR EACH ROW EXECUTE FUNCTION protect_guide_revision_body_redaction();

CREATE OR REPLACE FUNCTION protect_guide_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Guide revisions are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.canonical_form IS NULL OR NEW.canonical_form IS NOT NULL OR NEW.body_redacted_at IS NULL
           OR OLD.body_redacted_at IS NOT NULL
           OR (to_jsonb(NEW) - 'canonical_form' - 'body_redacted_at')
                IS DISTINCT FROM (to_jsonb(OLD) - 'canonical_form' - 'body_redacted_at')
           OR current_setting('life_in_uk.guide_body_redaction', true) IS DISTINCT FROM 'on'
           OR NOT EXISTS (
                SELECT 1 FROM guide_revision_body_redaction audit
                WHERE audit.revision_id = OLD.id AND audit.slug = OLD.slug
                    AND audit.draft_digest = OLD.draft_digest AND audit.redacted_by <> ''
           ) THEN
            RAISE EXCEPTION 'Guide revisions are immutable except approved superseded body redaction' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.body_redacted_at IS NOT NULL THEN
        RAISE EXCEPTION 'New Guide revisions cannot start redacted' USING ERRCODE = '23514';
    END IF;
    IF NEW.draft_digest <> encode(sha256(convert_to(NEW.canonical_form, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'Guide revision digest does not match its canonical form' USING ERRCODE = '23514';
    END IF;
    BEGIN
        document := NEW.canonical_form::jsonb;
    EXCEPTION WHEN others THEN
        RAISE EXCEPTION 'Guide revision canonical form is not valid JSON' USING ERRCODE = '23514';
    END;
    IF document ->> 'canonicalization' IS DISTINCT FROM NEW.canonicalization
        OR document ->> 'slug' IS DISTINCT FROM NEW.slug
        OR document ->> 'status' IS DISTINCT FROM 'DRAFT'
        OR document -> 'publishedAt' IS DISTINCT FROM 'null'::jsonb
        OR jsonb_typeof(document -> 'evidence') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Guide revision must be canonical DRAFT content for its slug with explicit evidence'
            USING ERRCODE = '23514';
    END IF;
    NEW.created_at := clock_timestamp();
    NEW.db_session_user := session_user;
    RETURN NEW;
END;
$$;

CREATE PROCEDURE redact_superseded_guide_body(p_revision_id uuid, p_redacted_by text, p_reason text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE
    v_slug varchar(160);
    v_digest char(64);
    v_old_publication uuid;
    v_replacement_publication uuid;
    v_lock_slug varchar(160);
BEGIN
    IF p_redacted_by IS NULL OR btrim(p_redacted_by) = '' OR length(p_redacted_by) > 100
       OR p_redacted_by ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'A valid redaction operator is required' USING ERRCODE = '22023';
    END IF;
    IF p_reason IS NULL OR btrim(p_reason) = '' OR length(p_reason) > 2000 THEN
        RAISE EXCEPTION 'A nonblank redaction reason of at most 2000 characters is required' USING ERRCODE = '22023';
    END IF;

    SELECT r.slug INTO v_lock_slug FROM guide_revision r WHERE r.id = p_revision_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Guide revision not found' USING ERRCODE = '23503'; END IF;
    -- Match the application lock order used by batch publication: batch slug, publication slug, revision slug.
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-content-batch:' || v_lock_slug, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-publication:' || v_lock_slug, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-revision:' || v_lock_slug, 0));

    SELECT r.slug, r.draft_digest, old_pub.id, new_pub.id
      INTO v_slug, v_digest, v_old_publication, v_replacement_publication
      FROM guide_revision r
      JOIN guide_publication old_pub ON old_pub.revision_id = r.id
      JOIN guide_publication new_pub ON new_pub.slug = r.slug AND new_pub.ended_at IS NULL
      JOIN guide g ON g.slug = r.slug AND g.status = 'PUBLISHED'
     WHERE r.id = p_revision_id AND r.canonical_form IS NOT NULL
       AND old_pub.ended_at IS NOT NULL AND old_pub.end_reason = 'REPLACED'
       AND new_pub.revision_id <> r.id
       AND NOT EXISTS (
            SELECT 1 FROM guide_content_batch pending
             WHERE pending.source_revision_id = r.id
               AND pending.status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED')
       )
       AND NOT EXISTS (
            SELECT 1 FROM guide_locale_current current_locale
            JOIN guide_content_batch current_batch ON current_batch.id = current_locale.batch_id
             WHERE current_batch.source_revision_id = r.id
       )
     FOR UPDATE OF r;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Guide revision is not safely superseded or is still in use' USING ERRCODE = '23514';
    END IF;

    INSERT INTO guide_revision_body_redaction(revision_id, slug, draft_digest, replaced_publication_id,
        replacement_publication_id, redacted_by, reason)
    VALUES (p_revision_id, v_slug, v_digest, v_old_publication, v_replacement_publication, p_redacted_by, p_reason);
    PERFORM set_config('life_in_uk.guide_body_redaction', 'on', true);
    UPDATE guide_revision SET canonical_form = NULL, body_redacted_at = clock_timestamp()
     WHERE id = p_revision_id;
END;
$$;

-- Only the schema owner can run this until a separately approved retention-operator grant is provisioned.
REVOKE ALL ON PROCEDURE redact_superseded_guide_body(uuid, text, text) FROM PUBLIC;
