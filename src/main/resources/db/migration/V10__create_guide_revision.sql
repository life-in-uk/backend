-- Immutable Guide content revisions (Issue #48). Not read by any public query; does not touch the guide projection.
CREATE TABLE guide_revision (
    id uuid PRIMARY KEY,
    slug varchar(160) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    revision_number integer NOT NULL CHECK (revision_number >= 1),
    canonicalization varchar(40) NOT NULL CHECK (canonicalization = 'guide-content-v1'),
    canonical_form text NOT NULL,
    draft_digest char(64) NOT NULL CHECK (draft_digest ~ '^[0-9a-f]{64}$'),
    based_on_revision_id uuid,
    created_at timestamptz NOT NULL,
    created_by varchar(100) NOT NULL CHECK (btrim(created_by) <> ''),
    db_session_user name NOT NULL,
    ai_assisted boolean NOT NULL,
    note varchar(2000) CHECK (note IS NULL OR btrim(note) <> ''),
    UNIQUE (slug, draft_digest),
    UNIQUE (slug, revision_number),
    UNIQUE (slug, id),
    -- A parent revision must belong to the same Guide slug.
    FOREIGN KEY (slug, based_on_revision_id) REFERENCES guide_revision(slug, id)
);

-- Revisions are append-only. On insert the database itself checks that the stored text is canonical
-- DRAFT content for the row's slug, with an explicit evidence collection, and that the digest is the
-- SHA-256 of exactly that text. Creation time and session are recorded by the database.
CREATE FUNCTION protect_guide_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document jsonb;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Guide revisions are immutable' USING ERRCODE = '23514';
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
CREATE TRIGGER guide_revision_history BEFORE INSERT OR UPDATE OR DELETE ON guide_revision
    FOR EACH ROW EXECUTE FUNCTION protect_guide_revision();
