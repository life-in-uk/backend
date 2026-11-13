-- Corrective hardening for V14. This migration changes no Guide content and redacts nothing.
-- The schema owner remains the only principal able to run the SECURITY DEFINER procedure.

CREATE OR REPLACE FUNCTION protect_guide_revision_body_redaction_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE procedure_owner name;
BEGIN
    SELECT pg_get_userbyid(p.proowner)::name INTO procedure_owner
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = TG_TABLE_SCHEMA AND p.proname = 'redact_superseded_guide_body'
       AND p.prokind = 'p' AND p.pronargs = 3
     LIMIT 1;
    IF procedure_owner IS NULL OR current_user <> procedure_owner
       OR current_setting('life_in_uk.guide_body_redaction', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Guide redaction audit rows may only be written by the controlled redaction procedure'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM guide_revision r
        JOIN guide_publication old_pub ON old_pub.revision_id = r.id
        JOIN guide_publication new_pub ON new_pub.slug = r.slug AND new_pub.ended_at IS NULL
        JOIN guide g ON g.slug = r.slug AND g.status = 'PUBLISHED'
        WHERE r.id = NEW.revision_id AND r.slug = NEW.slug AND r.draft_digest = NEW.draft_digest
          AND r.canonical_form IS NOT NULL AND old_pub.id = NEW.replaced_publication_id
          AND old_pub.ended_at IS NOT NULL AND old_pub.end_reason = 'REPLACED'
          AND new_pub.id = NEW.replacement_publication_id AND new_pub.revision_id <> r.id
          AND NOT EXISTS (SELECT 1 FROM guide_content_batch b WHERE b.source_revision_id = r.id
              AND b.status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED'))
          AND NOT EXISTS (SELECT 1 FROM guide_locale_current c JOIN guide_content_batch b ON b.id = c.batch_id
              WHERE b.source_revision_id = r.id)
    ) THEN
        RAISE EXCEPTION 'Guide redaction audit does not describe an eligible superseded body'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guide_revision_body_redaction_insert_guard
    BEFORE INSERT ON guide_revision_body_redaction
    FOR EACH ROW EXECUTE FUNCTION protect_guide_revision_body_redaction_insert();

CREATE OR REPLACE PROCEDURE redact_superseded_guide_body(p_revision_id uuid, p_redacted_by text, p_reason text)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE
    v_slug varchar(160);
    v_digest char(64);
    v_old_publication uuid;
    v_replacement_publication uuid;
BEGIN
    IF p_redacted_by IS NULL OR btrim(p_redacted_by) = '' OR length(p_redacted_by) > 100
       OR p_redacted_by ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'A valid redaction operator is required' USING ERRCODE = '22023';
    END IF;
    IF p_reason IS NULL OR btrim(p_reason) = '' OR length(p_reason) > 2000 THEN
        RAISE EXCEPTION 'A nonblank redaction reason of at most 2000 characters is required' USING ERRCODE = '22023';
    END IF;

    SELECT slug INTO v_slug FROM guide_revision WHERE id = p_revision_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Guide revision not found' USING ERRCODE = '23503'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-content-batch:' || v_slug, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-publication:' || v_slug, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('guide-revision:' || v_slug, 0));

    SELECT r.slug, r.draft_digest, old_pub.id, new_pub.id
      INTO v_slug, v_digest, v_old_publication, v_replacement_publication
      FROM guide_revision r
      JOIN guide_publication old_pub ON old_pub.revision_id = r.id
      JOIN guide_publication new_pub ON new_pub.slug = r.slug AND new_pub.ended_at IS NULL
      JOIN guide g ON g.slug = r.slug AND g.status = 'PUBLISHED'
     WHERE r.id = p_revision_id AND r.canonical_form IS NOT NULL
       AND old_pub.ended_at IS NOT NULL AND old_pub.end_reason = 'REPLACED'
       AND new_pub.revision_id <> r.id
       AND NOT EXISTS (SELECT 1 FROM guide_content_batch b WHERE b.source_revision_id = r.id
           AND b.status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED'))
       AND NOT EXISTS (SELECT 1 FROM guide_locale_current c JOIN guide_content_batch b ON b.id = c.batch_id
           WHERE b.source_revision_id = r.id)
     FOR UPDATE OF r;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Guide revision is not safely superseded or is still in use' USING ERRCODE = '23514';
    END IF;

    PERFORM set_config('life_in_uk.guide_body_redaction', 'on', true);
    BEGIN
        INSERT INTO guide_revision_body_redaction(revision_id, slug, draft_digest, replaced_publication_id,
            replacement_publication_id, redacted_by, reason)
        VALUES (p_revision_id, v_slug, v_digest, v_old_publication, v_replacement_publication, p_redacted_by, p_reason);
        UPDATE guide_revision SET canonical_form = NULL, body_redacted_at = clock_timestamp()
         WHERE id = p_revision_id;
        PERFORM set_config('life_in_uk.guide_body_redaction', 'off', true);
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('life_in_uk.guide_body_redaction', 'off', true);
        RAISE;
    END;
END;
$$;

REVOKE ALL ON PROCEDURE redact_superseded_guide_body(uuid, text, text) FROM PUBLIC;
