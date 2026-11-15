# Multilingual Guide lifecycle V1 (design proposal)

This proposal keeps a Guide slug as the topic identity and keeps the existing Chinese public Guide API response shape. It does not authorize applying a destructive conversion to the current database.

## Locale and batch model

- Canonical locales are `zh-CN` (default/source), `zh-TW`, `en`, `ur`, and `hi`.
- A content batch represents one candidate replacement for one Guide. It records `guide_id/slug`, the exact source `guide_revision.id`, policy/source version, validity interval, batch status, idempotency key, and timestamps.
- A locale revision belongs to one batch and has one canonical locale, validated content, SHA-256 hash, reviewer result/hash, and its own review/publication state. `zh-CN` resolves to the source `guide_revision`; the other four rows reference that exact source revision ID. The database/service must enforce one and only one row per required locale before review/approval.
- Current public state is a five-row locale pointer set associated with one active batch. Do not maintain five unrelated “current” flags. Publication locks the Guide slug, validates that every locale is present, valid, reviewed against its exact content hash, and within its validity interval, then changes all five pointers and the Chinese `guide` projection in one transaction.
- Pending content is stored separately from current content. A failed locale leaves its batch pending and cannot alter the current pointer set. Retries use `(guide, source revision, locale, policy/source version)` idempotency.

## Expiry and retention

- `valid_until` is part of the active batch. Public Guide queries exclude an expired batch immediately using database time, regardless of whether replacement has completed. The existing `/api/guides` and `/api/guides/{slug}` DTOs remain unchanged; they simply omit an expired Guide / return the existing 404.
- After an approved replacement commits, the same transaction removes prior locale body rows and prior projection rows, while retaining only minimal hash, source revision ID, review outcome/hash, approval identity/time, and replacement/expiry timestamps where retention is required.
- Expiry/cleanup is a separate Spring Boot service operation. It must first hide expired data from public queries, then delete expired bodies transactionally. n8n may request a dry run but cannot execute deletion; owner approval is a separate authenticated action.
- Retention for legal/security audit and database backups is not content availability. Existing backups may still contain prior bodies until their approved retention expires.

## V10/V11 compatibility and migration boundary

V10 `guide_revision` is append-only: its trigger rejects `UPDATE` and `DELETE`, and `canonical_form` is `NOT NULL`. V11 `guide_publication` is append-only history with FK references to V10 revisions. V11 activation checks protect its publication history, but `--import-guide` still writes the public projection directly and there is no projection guard trigger in V11.

The minimum full lifecycle change needs a reviewed V12 migration (not yet placed in Flyway's active migration directory):

1. Add batch, locale revision/body, review, and active-locale pointer tables with unique locale-per-batch and FKs to `guide`/`guide_revision`.
2. Add a transactional projection-write guard. Only `GuidePublicationService` may set a transaction-local capability before it updates the public projection. Add guards to `guide`, `guide_source`, `guide_evidence`, and `guide_evidence_support`.
3. Add a one-way body-redaction path for superseded V10 rows: preserve revision ID, slug, digest, creator, time, parent and AI metadata; clear the canonical body only after replacement publication commits. Remove the `NOT NULL` constraint and revise the trigger to permit only that exact one-way redaction. Do not delete publication metadata or revision IDs still referenced by V11.
4. Add a validity-aware public read projection while retaining the current Guide DTO contract.

Step 3 is a destructive data-retention operation. Existing V10 bodies must not be rewritten by an unattended Flyway startup. Keep the body-redaction conversion as a separately owner-run, reviewed operation after backup and test rehearsal. No existing data has been converted here. Until that operation is approved and completed, strict “no old article bodies” retention is not met.

## Publication sequence

`DRAFT -> VALIDATED -> REVIEWED -> APPROVED -> PUBLISHED`; every transition records actor/time and exact locale hashes. The only write that changes current content is `APPROVED -> PUBLISHED`, in one Spring transaction. Human approval must identify the complete five-locale batch and its batch hash. No model or n8n credential can call approval or publication.

## Existing import bypass

The legacy `--import-guide` command is disabled in application code. `GuideImporter` remains an internal projection writer used by publication. The proposed V12 write guard is required for a database-level boundary; disabling the CLI alone does not constrain the table owner or arbitrary direct SQL.
