ALTER TABLE guide_source ADD COLUMN editorial_key varchar(160)
    CHECK (editorial_key IS NULL OR editorial_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
ALTER TABLE guide_source ADD CONSTRAINT guide_source_editorial_key_unique UNIQUE (guide_id, editorial_key);

CREATE TABLE guide_evidence (
    id uuid PRIMARY KEY,
    guide_id uuid NOT NULL REFERENCES guide(id) ON DELETE CASCADE,
    editorial_key varchar(160) NOT NULL CHECK (editorial_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    statement text NOT NULL CHECK (btrim(statement) <> ''),
    evidence_order integer NOT NULL CHECK (evidence_order >= 0),
    UNIQUE (guide_id, editorial_key),
    UNIQUE (guide_id, evidence_order),
    UNIQUE (guide_id, id)
);

CREATE TABLE guide_evidence_support (
    id uuid PRIMARY KEY,
    guide_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    source_key varchar(160) NOT NULL,
    support_order integer NOT NULL CHECK (support_order >= 0),
    locator text CHECK (locator IS NULL OR btrim(locator) <> ''),
    excerpt text CHECK (excerpt IS NULL OR btrim(excerpt) <> ''),
    note text NOT NULL CHECK (btrim(note) <> ''),
    CHECK (locator IS NOT NULL OR excerpt IS NOT NULL),
    UNIQUE (evidence_id, support_order),
    FOREIGN KEY (guide_id, evidence_id) REFERENCES guide_evidence(guide_id, id) ON DELETE CASCADE,
    FOREIGN KEY (guide_id, source_key) REFERENCES guide_source(guide_id, editorial_key)
);
CREATE INDEX guide_evidence_support_source_idx ON guide_evidence_support(guide_id, source_key);
