-- Guide publication history and workflow activation (Issue #50). Activation stays empty until the
-- activation milestone; until then no non-legacy publication can be recorded or ended.
CREATE TABLE guide_workflow_activation (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    activated_at timestamptz NOT NULL,
    activated_by varchar(100) NOT NULL CHECK (btrim(activated_by) <> ''),
    db_session_user name NOT NULL
);

CREATE FUNCTION protect_guide_workflow_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Workflow activation cannot be changed or removed' USING ERRCODE = '23514';
    END IF;
    NEW.activated_at := clock_timestamp();
    NEW.db_session_user := session_user;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guide_workflow_activation_history BEFORE INSERT OR UPDATE OR DELETE ON guide_workflow_activation
    FOR EACH ROW EXECUTE FUNCTION protect_guide_workflow_activation();

CREATE TABLE guide_publication (
    id uuid PRIMARY KEY,
    slug varchar(160) NOT NULL,
    revision_id uuid NOT NULL,
    published_at timestamptz NOT NULL,
    published_digest char(64) NOT NULL CHECK (published_digest ~ '^[0-9a-f]{64}$'),
    published_by varchar(100) NOT NULL CHECK (btrim(published_by) <> ''),
    db_session_user name NOT NULL,
    checklist_id varchar(40) NOT NULL,
    verified_on date,
    legacy boolean NOT NULL DEFAULT false,
    ended_at timestamptz,
    end_reason varchar(20) CHECK (end_reason IN ('REPLACED', 'WITHDRAWN')),
    ended_by varchar(100) CHECK (ended_by IS NULL OR btrim(ended_by) <> ''),
    ended_db_session_user name,
    end_note varchar(2000) CHECK (end_note IS NULL OR btrim(end_note) <> ''),
    -- A revision is published at most once, and only as the slug it belongs to.
    UNIQUE (revision_id),
    FOREIGN KEY (slug, revision_id) REFERENCES guide_revision(slug, id),
    CHECK ((ended_at IS NULL AND end_reason IS NULL AND ended_by IS NULL AND ended_db_session_user IS NULL AND end_note IS NULL)
        OR (ended_at IS NOT NULL AND end_reason IS NOT NULL AND ended_by IS NOT NULL AND ended_db_session_user IS NOT NULL)),
    CHECK (end_reason IS DISTINCT FROM 'WITHDRAWN' OR end_note IS NOT NULL),
    CHECK (ended_at IS NULL OR ended_at >= published_at),
    CHECK ((legacy AND checklist_id = 'legacy-baseline' AND verified_on IS NULL)
        OR (NOT legacy AND checklist_id IN ('standard-v1', 'family-visa-v1') AND verified_on IS NOT NULL))
);
-- At most one open (public) publication per slug.
CREATE UNIQUE INDEX guide_publication_open_slug ON guide_publication(slug) WHERE ended_at IS NULL;
CREATE INDEX guide_publication_slug_history ON guide_publication(slug, published_at DESC);

-- History cannot be deleted or rewritten. The only change allowed is ending an open publication once,
-- and non-legacy publications can be recorded or ended only after workflow activation.
CREATE FUNCTION protect_guide_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Guide publication history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT NEW.legacy AND NOT EXISTS (SELECT 1 FROM guide_workflow_activation) THEN
            RAISE EXCEPTION 'Guide publication requires workflow activation' USING ERRCODE = '23514';
        END IF;
        IF NEW.ended_at IS NOT NULL THEN
            RAISE EXCEPTION 'A new publication must be open' USING ERRCODE = '23514';
        END IF;
        NEW.db_session_user := session_user;
        RETURN NEW;
    END IF;
    IF OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'An ended publication cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id OR NEW.slug <> OLD.slug OR NEW.revision_id <> OLD.revision_id
        OR NEW.published_at <> OLD.published_at OR NEW.published_digest <> OLD.published_digest
        OR NEW.published_by <> OLD.published_by OR NEW.db_session_user <> OLD.db_session_user
        OR NEW.checklist_id <> OLD.checklist_id OR NEW.verified_on IS DISTINCT FROM OLD.verified_on
        OR NEW.legacy <> OLD.legacy OR NEW.ended_at IS NULL THEN
        RAISE EXCEPTION 'Only ending an open publication is allowed' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM guide_workflow_activation) THEN
        RAISE EXCEPTION 'Ending a publication requires workflow activation' USING ERRCODE = '23514';
    END IF;
    NEW.ended_db_session_user := session_user;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guide_publication_history BEFORE INSERT OR UPDATE OR DELETE ON guide_publication
    FOR EACH ROW EXECUTE FUNCTION protect_guide_publication();
