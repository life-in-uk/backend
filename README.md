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
records history only; acquisition is connected separately in Issue #6 below.
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

## Controlled Bank Holidays acquisition (Issue #6)

`BankHolidaysAcquisition.acquire()` is an internal, parameterless application
capability. It is not invoked on startup, exposed by a controller, or scheduled.
Each call performs one attempt, without application retry/backoff. It neither
reads nor changes polling eligibility/frequency policy.

The source-owned `requireBankHolidays()` lookup resolves the expected configured
endpoint and requires qualification, endpoint/source enablement, and the approved
source identity/URL. Acquisition does not create, qualify or enable configuration.
The GET destination comes from that endpoint; there is no caller URL argument,
runtime destination override, or second URL literal in acquisition code.

Java 21 `HttpClient` uses a 5-second connection timeout and 20-second whole-response
deadline covering headers and body. Redirects are never followed. Requests identify
as `LifeInUK/0.0.1 (Bank Holidays acquisition)`, accept `application/json`, and request
identity encoding. Only HTTP 200 with exactly one valid `application/json`
Content-Type (parameters allowed; max 200 characters), identity/no content encoding,
and a non-empty body is accepted. Other statuses, media types, compressed responses
and empty bodies fail. JSON syntax and Bank Holidays content are not interpreted.

The maximum response body is **1 MiB (1,048,576 bytes)**, allowing ample room for a
small calendar JSON document. Both declared length and actually received bytes are
checked; chunked responses cannot bypass the cap. No decoding, decompression,
normalization or reserialization occurs. The original Content-Type is retained.
The artifact constructor hashes exactly the stored bytes; PostgreSQL independently
checks SHA-256, with no deduplication across observations.

The acquisition boundary rejects an ambient database transaction. Source resolution
and STARTED persistence use short transactions; HTTP runs with no DB transaction.
Evidence insert and SUCCESS completion commit atomically in another short
transaction. Failure recording uses a separate short transaction, with fixed bounded
messages/categories for HTTP, network, timeout, size, response characteristics,
interruption and evidence persistence failures. Response/error dumps are not stored.
If failure recording is unavailable or commit outcome is uncertain, an exception
propagates; the application does not invent a terminal outcome. A persisted STARTED
run may require later operator investigation; no recovery workflow is added here.

Automated acquisition tests use a local JDK HTTP server, real Java HTTP transport,
and the accepted isolated PostgreSQL harness in a separate test schema. A test-only
client redirects the original configured request to loopback and asserts its original
destination; there is no production target override. Tests cover raw-byte fidelity,
qualification, HTTP contract, limits, deadlines (including stalled body), redirect
rejection, transaction boundaries, persistence rollback and independent observations.
No automated test accesses GOV.UK or requires internet.

## Deterministic Bank Holidays interpretation (Issue #8)

`GovUkBankHolidaysParser.parse(EvidenceArtifact)` interprets one supplied artifact.
It performs no acquisition, evidence selection, repository access or writes. The
caller supplies the historical observation to interpret; current qualification or
polling policy is not re-evaluated when interpreting already acquired evidence.
Raw evidence remains immutable source truth. Interpretation is downstream and
uses the existing Jackson 3 dependency, with no AI or HTTP involvement.

The in-memory result carries the exact artifact UUID and an immutable list of
facts: explicit division enum, unchanged title/notes, validated `LocalDate`, and
boolean bunting. All three supported divisions must be present, with matching
nested `division` identifiers and `events` arrays. Empty arrays and empty notes
are valid; titles must be nonblank. Strings are not trimmed, events are not sorted
or deduplicated, and no year filtering occurs. Division groups use fixed enum order.

Validation requires exactly the supported root/division/event fields, rejects
unknown fields/divisions, duplicate JSON keys, trailing documents, missing/null
fields and wrong types, and accepts only valid `YYYY-MM-DD` dates. No scalar
coercion or factual defaults are applied. Contract changes therefore require an
explicit parser update. Errors identify a structural location without dumping
source values or payloads.

No durable interpreted records are required by Issue #8 or the accepted current
architecture. Interpretation remains in memory; durable domain/publication
persistence is intentionally deferred. No Flyway migration or parser repository
is added. Parser fixtures are authored offline and never read development evidence.
