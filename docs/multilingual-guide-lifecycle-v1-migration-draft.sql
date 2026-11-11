-- REVIEW DRAFT ONLY. Not an active Flyway migration and not executed.
-- The body-retention/redaction section requires explicit owner approval after backup rehearsal.

CREATE TABLE guide_content_batch (
    id uuid PRIMARY KEY,
    slug varchar(160) NOT NULL REFERENCES guide(slug),
    source_revision_id uuid NOT NULL,
    source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    policy_version varchar(120) NOT NULL CHECK (btrim(policy_version) <> ''),
    source_version varchar(120) NOT NULL CHECK (btrim(source_version) <> ''),
    idempotency_key varchar(200) NOT NULL UNIQUE CHECK (btrim(idempotency_key) <> ''),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'VALIDATED', 'REVIEWED', 'APPROVED', 'PUBLISHED', 'EXPIRED', 'SUPERSEDED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    approved_at timestamptz,
    approved_by varchar(100),
    published_at timestamptz,
    UNIQUE (id, source_revision_id),
    UNIQUE (slug, source_revision_id, policy_version, source_version),
    FOREIGN KEY (slug, source_revision_id) REFERENCES guide_revision(slug, id),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK ((approved_at IS NULL AND approved_by IS NULL) OR (approved_at IS NOT NULL AND btrim(approved_by) <> '')),
    CHECK ((status IN ('APPROVED', 'PUBLISHED', 'SUPERSEDED') AND approved_at IS NOT NULL) OR status NOT IN ('APPROVED', 'PUBLISHED', 'SUPERSEDED'))
);

CREATE TABLE guide_locale_revision (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES guide_content_batch(id),
    locale varchar(5) NOT NULL CHECK (locale IN ('zh-CN', 'zh-TW', 'en', 'ur', 'hi')),
    source_revision_id uuid NOT NULL,
    content_body jsonb,
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    validation_status varchar(20) NOT NULL CHECK (validation_status IN ('PENDING', 'VALID', 'FAILED')),
    review_status varchar(20) NOT NULL CHECK (review_status IN ('PENDING', 'PASSED', 'FAILED')),
    reviewed_hash char(64),
    reviewed_by varchar(100),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (batch_id, locale),
    UNIQUE (batch_id, locale, id),
    FOREIGN KEY (batch_id, source_revision_id) REFERENCES guide_content_batch(id, source_revision_id),
    CHECK (reviewed_hash IS NULL OR reviewed_hash ~ '^[0-9a-f]{64}$'),
    CHECK ((review_status = 'PASSED' AND reviewed_hash = content_hash AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR review_status <> 'PASSED')
);

CREATE TABLE guide_locale_current (
    slug varchar(160) NOT NULL REFERENCES guide(slug),
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

-- Runtime publication transaction must lock the Guide slug and enforce all five locales,
-- VALID validation state, PASSED review state, reviewed_hash = content_hash, approval, and validity
-- before changing any current row or the Chinese guide projection. It then deletes prior bodies
-- while retaining revision/hash/review metadata. These invariants are not expressible as simple
-- row CHECK constraints and must be enforced in a single Spring Boot service transaction.

-- Destructive, separately approved conversion (do not run as ordinary Flyway startup):
-- ALTER TABLE guide_revision ALTER COLUMN canonical_form DROP NOT NULL;
-- Replace protect_guide_revision() to permit only one-way canonical_form -> NULL redaction while
-- every identity, digest, provenance, and parent field remains unchanged. Preserve V11 rows/FKs.
-- Existing bodies are redacted only after approved replacement publication commits.
