# Life in UK backend

## Source foundation (Issue #2)

Flyway creates `source` and `source_endpoint`; Hibernate uses `ddl-auto: validate`.
Startup inserts the GOV.UK Bank Holidays configuration if absent. It performs no
HTTP requests and runs no polling timer. Existing records, IDs, enablement,
qualification, versions and polling eligibility are never overwritten by bootstrap.

The only supported endpoint is `https://www.gov.uk/bank-holidays.json`. Its URL is
fixed in the model and constrained in PostgreSQL. The calendar scope is rolling
`CURRENT_YEAR` in Europe/London, with daily polling intent and a persisted
`next_poll_at`. These are policy data, not a scheduler or observation history.

Configuration is **not permission**. New configuration defaults to `PENDING`.
To initialize a **new** qualified endpoint, explicitly supply all three properties:

- `life-in-uk.source.bank-holidays.qualified=true`
- `life-in-uk.source.bank-holidays.qualification-record=<owner qualification decision>`
- `life-in-uk.source.bank-holidays.use-retention-policy=<approved use, attribution and retention policy>`

Use standard Spring configuration/environment binding. The qualification input is
an owner decision, not a legal approval inferred by the application. Setting these
properties on a later restart does not upgrade an existing pending endpoint.
Existing policy changes require a deliberate source-owned operation; there is no
management API in this issue. Both source and endpoint must be enabled, and the
endpoint explicitly qualified, before `isQualifiedAndEnabled()` returns true.

## Tests

Run `mvn test` and `mvn verify` as a non-root user with Java 21 and PostgreSQL 16
server binaries installed. On Ubuntu the default binary path is
`/usr/lib/postgresql/16/bin`; elsewhere set `TEST_POSTGRES_BIN` to their directory.

Tests automatically initialize an empty temporary PostgreSQL cluster, bind it to
loopback on a temporary port, apply Flyway, validate with Hibernate, and stop/remove
the cluster when the test JVM exits. No manually prepared database is needed.
Tests never read `DB_URL`, `DB_USERNAME` or `DB_PASSWORD`; there is no development
database fallback. A test without the initializer fails against an intentionally
unusable test URL. Missing binaries fail the suite rather than skipping it.

No GOV.UK, Ollama, Docker or additional runtime/testing service is required.
After Maven dependencies have been cached, the tests can run offline.

## Ingestion and evidence foundation (Issue #4)

Flyway V2 adds evidence-owned `ingestion_run` and `evidence_artifact`. This slice
records history only: no acquisition, parsing, scheduling or API is implemented.
`QualifiedSourceEndpoints.requireQualified(id)` is a source-owned read boundary;
source repositories remain package-private. Both the run factory and database
insertion require an existing qualified, enabled endpoint and enabled source.
Later disablement does not erase or prevent completion of an existing attempt.

Runs use UUIDs and Instant timestamps (`TIMESTAMP WITH TIME ZONE`). A run starts
as `STARTED` and can finish once as `SUCCESS` or `FAILED`. Completion cannot
precede its start or stored observations. Failed runs require a nonblank code
(max 100 characters) and message (max 1000); other states have neither. Optimistic
locking and database triggers protect identity and completed outcomes. Historical
runs cannot be deleted.

Artifacts preserve non-empty raw bytes in PostgreSQL `bytea`, a media type
(max 200 characters), observation time, and a lowercase 64-character SHA-256.
The constructor hashes the exact supplied bytes without decoding, parsing or
normalization. PostgreSQL independently checks the digest using its built-in
SHA-256 function; no extension is needed. The hash is deliberately not unique:
identical bytes observed in different runs remain distinct historical evidence.

Artifacts expose defensive byte copies and no mutation methods. Their
package-private persistence boundary offers append via JPA `persist` and lookup,
without merge/update/delete. Hibernate marks artifacts immutable; PostgreSQL
triggers reject row updates and deletes, including direct SQL and bulk JPA
attempts. These protections apply to ordinary DML; privileged schema operations
are outside the application boundary. No retention/deletion workflow is added.

Provenance follows artifact → run → endpoint → source. V1 already fixes endpoint
URL/key; V2 prevents endpoint identity/ownership changes once observed. Mutable
qualification and policy remain current configuration, not a historical policy
snapshot. No copied source configuration, acquisition type, generic metadata or
response headers are added without an acquisition consumer requiring them.

Evidence tests use the same isolated PostgreSQL initializer as source tests and
fixture bytes only. The source schema inventory test now expects both migrations
and all four foundation tables; accepted V1 and source behaviour remain intact.
