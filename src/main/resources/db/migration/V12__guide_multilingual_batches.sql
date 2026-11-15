-- Five-locale draft batches and current locale pointers. Existing Guide rows and V10/V11 history remain intact.
CREATE TABLE guide_content_batch (
    id uuid PRIMARY KEY,
    slug varchar(160) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    source_revision_id uuid NOT NULL,
    source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key varchar(200) NOT NULL UNIQUE CHECK (btrim(idempotency_key) <> ''),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    policy_version varchar(120) NOT NULL CHECK (btrim(policy_version) <> ''),
    source_version varchar(120) NOT NULL CHECK (btrim(source_version) <> ''),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'READY', 'APPROVED', 'PUBLISHED', 'EXPIRED', 'SUPERSEDED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by varchar(100) NOT NULL CHECK (btrim(created_by) <> ''),
    approved_at timestamptz,
    approved_by varchar(100),
    published_at timestamptz,
    UNIQUE (slug, source_revision_id, policy_version, source_version),
    UNIQUE (id, source_revision_id),
    FOREIGN KEY (slug, source_revision_id) REFERENCES guide_revision(slug, id),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK ((approved_at IS NULL AND approved_by IS NULL)
        OR (approved_at IS NOT NULL AND approved_by IS NOT NULL AND btrim(approved_by) <> '')),
    CHECK ((status IN ('APPROVED', 'PUBLISHED') AND approved_at IS NOT NULL) OR status NOT IN ('APPROVED', 'PUBLISHED'))
);
CREATE INDEX guide_content_batch_slug_idx ON guide_content_batch(slug, created_at DESC);

CREATE TABLE guide_locale_revision (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL,
    locale varchar(5) NOT NULL CHECK (locale IN ('zh-CN', 'zh-TW', 'en', 'ur', 'hi')),
    source_revision_id uuid NOT NULL,
    canonical_form text,
    content_hash char(64) CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'),
    validation_status varchar(20) NOT NULL CHECK (validation_status IN ('VALID', 'FAILED', 'SUPERSEDED')),
    failure_code varchar(60),
    review_status varchar(20) NOT NULL CHECK (review_status IN ('PENDING', 'PASSED', 'FAILED')),
    reviewed_hash char(64),
    review_result jsonb,
    reviewer varchar(100),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (batch_id, locale),
    UNIQUE (batch_id, locale, id),
    FOREIGN KEY (batch_id, source_revision_id) REFERENCES guide_content_batch(id, source_revision_id),
    CHECK (reviewed_hash IS NULL OR reviewed_hash ~ '^[0-9a-f]{64}$'),
    CHECK ((validation_status = 'VALID' AND canonical_form IS NOT NULL AND content_hash IS NOT NULL AND failure_code IS NULL)
        OR (validation_status = 'FAILED' AND canonical_form IS NULL AND failure_code IS NOT NULL)
        OR (validation_status = 'SUPERSEDED' AND canonical_form IS NULL AND content_hash IS NOT NULL)),
    CHECK ((review_status = 'PASSED' AND reviewed_hash = content_hash AND reviewer IS NOT NULL AND reviewed_at IS NOT NULL)
        OR review_status <> 'PASSED')
);
CREATE INDEX guide_locale_revision_source_idx ON guide_locale_revision(source_revision_id, locale);

CREATE TABLE guide_locale_current (
    slug varchar(160) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    locale varchar(5) NOT NULL CHECK (locale IN ('zh-CN', 'zh-TW', 'en', 'ur', 'hi')),
    batch_id uuid NOT NULL,
    locale_revision_id uuid NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    PRIMARY KEY (slug, locale),
    FOREIGN KEY (batch_id) REFERENCES guide_content_batch(id),
    FOREIGN KEY (batch_id, locale, locale_revision_id) REFERENCES guide_locale_revision(batch_id, locale, id),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX guide_locale_current_expiry_idx ON guide_locale_current(valid_until) WHERE valid_until IS NOT NULL;

-- After workflow activation, only GuidePublicationService's transaction-local write capability may alter
-- the public projection. This preserves the existing pre-activation bootstrap behavior while closing the
-- direct projection write path after activation. V10 and V11 revision/publication triggers are unchanged.
CREATE FUNCTION protect_guide_projection_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM guide_workflow_activation)
        AND current_setting('life_in_uk.guide_publication', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Guide projection writes require GuidePublicationService' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guide_projection_write_guard BEFORE INSERT OR UPDATE OR DELETE ON guide
    FOR EACH ROW EXECUTE FUNCTION protect_guide_projection_write();
CREATE TRIGGER guide_source_projection_write_guard BEFORE INSERT OR UPDATE OR DELETE ON guide_source
    FOR EACH ROW EXECUTE FUNCTION protect_guide_projection_write();
CREATE TRIGGER guide_evidence_projection_write_guard BEFORE INSERT OR UPDATE OR DELETE ON guide_evidence
    FOR EACH ROW EXECUTE FUNCTION protect_guide_projection_write();
CREATE TRIGGER guide_evidence_support_projection_write_guard BEFORE INSERT OR UPDATE OR DELETE ON guide_evidence_support
    FOR EACH ROW EXECUTE FUNCTION protect_guide_projection_write();
