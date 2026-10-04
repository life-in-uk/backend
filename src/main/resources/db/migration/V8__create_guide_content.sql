CREATE TABLE guide (
    id uuid PRIMARY KEY,
    slug varchar(160) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    category varchar(100) NOT NULL CHECK (category ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title varchar(200) NOT NULL CHECK (btrim(title) <> ''),
    summary varchar(1000) NOT NULL CHECK (btrim(summary) <> ''),
    content text NOT NULL CHECK (btrim(content) <> ''),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    published_at timestamptz,
    updated_at timestamptz NOT NULL,
    CHECK ((status = 'DRAFT' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL AND updated_at >= published_at))
);
CREATE INDEX guide_public_list_idx ON guide(status, published_at DESC, slug);

CREATE TABLE guide_source (
    id uuid PRIMARY KEY,
    guide_id uuid NOT NULL REFERENCES guide(id) ON DELETE CASCADE,
    organisation varchar(200) NOT NULL CHECK (btrim(organisation) <> ''),
    title varchar(300) NOT NULL CHECK (btrim(title) <> ''),
    url varchar(2000) NOT NULL CHECK (btrim(url) <> ''),
    accessed_at timestamptz NOT NULL,
    source_order integer NOT NULL CHECK (source_order >= 0),
    UNIQUE (guide_id, source_order)
);
