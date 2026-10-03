# Life in UK backend

## Source foundation (Issue #2)

Flyway creates `source` and `source_endpoint`; Hibernate uses `ddl-auto: validate`.
Startup inserts the GOV.UK Bank Holidays configuration if absent. It performs no
HTTP requests and runs no polling timer. Existing records, IDs, enablement,
qualification, versions and polling eligibility are never overwritten by bootstrap.

The Bank Holidays endpoint is `https://www.gov.uk/bank-holidays.json`. Its URL is
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
fixture bytes only. The source schema inventory test covers all accepted migrations
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

## Read-only Bank Holidays API (Issue #10)

`GET /api/bank-holidays` reads existing evidence through `BankHolidaysQuery` and
an evidence-owned `BankHolidaysEvidence` selection boundary, then reuses the
accepted parser. GET never invokes acquisition, qualification or scheduling.
Callers cannot select a URL, source, endpoint, run or artifact. Extra query
parameters do not influence selection.

Selection requires the canonical Bank Holidays source key/scope and endpoint
key/URL, an enabled source and endpoint, and current explicit qualification.
This public read policy fails closed when configuration is pending or disabled;
it does not change the parser's ability to interpret supplied historical evidence.
Only SUCCESS runs with an artifact participate. The latest run is ordered by
completion time descending, then PostgreSQL UUID ordering descending. Within a
run, artifact observation time and artifact UUID descending resolve ties. A
malformed selected artifact fails rather than falling back to older evidence.

The response is an explicit DTO:

```json
{
  "evidence": {"artifactId": "<UUID>", "observedAt": "<UTC ISO instant>"},
  "divisions": [
    {"division": "england-and-wales", "events": [
      {"title": "<source title>", "date": "YYYY-MM-DD", "notes": "", "bunting": true}
    ]},
    {"division": "scotland", "events": []},
    {"division": "northern-ireland", "events": []}
  ]
}
```

All three groups are returned in parser enum order. Events preserve source order,
whitespace, empty notes and duplicates; no year filtering occurs. Raw bytes,
digests, entities and source configuration are not exposed. Selection uses a short
read-only transaction and returns a detached artifact; parsing/mapping occur after
that transaction finishes, without lazy association access.

No eligible evidence returns HTTP **404**, with code `BANK_HOLIDAYS_UNAVAILABLE`.
Invalid selected evidence returns HTTP **500**, with code
`BANK_HOLIDAYS_INVALID_EVIDENCE`. Both return only fixed `code` and `message`
fields; neither acquires replacement data or exposes parser diagnostics.

No CORS allowance is added here. The current frontend is a local design showcase
with no backend fetches, and its Vite configuration does not establish a fixed
origin or proxy. The frontend integration issue must establish the actual origin
and any narrow development CORS allowance; wildcard origins are not enabled.

API tests use a real loopback HTTP server and the existing isolated PostgreSQL
process, with a separate test schema and authored fixtures. Acquisition is replaced
with a test mock and verified never invoked. Complete row snapshots prove GET does
not alter source configuration, run history or evidence. No development database,
live GOV.UK access, interpreted persistence, migration or dependency is needed.

## TfL Underground source foundation (Issue #12)

Transport for London is the second canonical provider, with source key
`transport-for-london`, display name `Transport for London`, and the currently
supported dataset scope `TFL_UNDERGROUND_STATUS`. One endpoint belongs to it:
`tfl-underground-status`, at `https://api.tfl.gov.uk/Line/Mode/tube/Status`.
TfL's [official API schema](https://api.tfl.gov.uk/swagger/docs/v1) documents
`GET /Line/Mode/{modes}/Status`, producing structured JSON; `tube` restricts it to
Underground. [Official request examples](https://content.tfl.gov.uk/example-api-requests.pdf)
identify this route as current Tube line status. No operational request was made
for this foundation.

V3 generalises V1's single-provider checks to these two controlled endpoint/URL
pairs and two scopes, preserving immutable endpoint keys/URLs and source scopes
with database triggers. Bank Holidays keeps its existing calendar and polling-policy
requirements. TfL has no calendar scope, polling interval or next-poll timestamp:
those existing columns are nullable for TfL only. No frequency is selected and no
scheduler is added. V1/V2, existing rows and evidence protections remain unchanged.

TfL bootstrap inserts missing canonical configuration only, with PENDING status
by default. The recorded pending policy is an **unapproved draft**, not permission.
`QualifiedSourceEndpoints.requireTflUnderground()` and `requireQualified(id)`
require source/endpoint enablement, QUALIFIED status, nonblank owner record and
use/retention policy, and canonical TfL ownership/scope/URL. Repositories remain
package-private and no URL input or public qualification interface is added.

To bootstrap a **new** explicitly qualified endpoint, supply all three properties:

- `life-in-uk.source.tfl-underground.qualified=true`
- `life-in-uk.source.tfl-underground.qualification-record=<explicit owner decision>`
- `life-in-uk.source.tfl-underground.use-retention-policy=<owner-approved use/licence/retention decision>`

The owner record should identify provider, Underground scope, official endpoint,
Travel Live use, retention and applicable attribution/licence obligations. Existing
qualification, records, policies, identities, versions and disabled state survive
restart, even if bootstrap properties change. Later approval uses the existing
source-owned `SourceEndpoint.qualify(record, policy)` operation; bootstrap does not
upgrade existing PENDING records. No TfL approval is fabricated by this issue.

The draft source-specific policy describes high-frequency raw evidence as immutable
while retained, with a default target of **approximately 72 hours from
EvidenceArtifact observation time**. This is a product target, not a TfL-mandated
TTL or an implemented deletion mechanism. Future Current State survives raw expiry
and follows its own update/expiry rules. Future meaningful normalized Change History
may be retained long-term; repeated unchanged polls must not become permanent
business-history rows. TfL terms override the default if they impose a different
retention/republication requirement. Bank Holidays policy is independent.

**Acquisition, parsing, Current State, Change History, cleanup and scheduling are
NOT IMPLEMENTED for TfL.** Existing evidence triggers still reject updates/deletes;
a future reviewed retention issue must establish an appropriate controlled expiry
mechanism without weakening immutability while retained.

Official documentation reviewed for this decision (2026-10-03):

- [TfL Unified API overview](https://tfl.gov.uk/info-for/open-data-users/unified-api):
  structured developer API for transport data.
- [Transport Data Service licence](https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service):
  permits copying, adapting and commercial/non-commercial reuse subject to terms.
  It requires `Powered by TfL Open Data`, OS and Geomni acknowledgements, protected
  branding/non-endorsement, and a maximum of 500 calls/minute per feed. It reserves
  throttling rights, requires ongoing terms review, and grants no use rights after
  licence termination. These are TfL-amended terms, not an unqualified OGL grant.
- [API portal products](https://api-portal.tfl.gov.uk/products): anonymous access is
  limited to 50 requests/minute; registered subscription access offers 500/minute,
  with higher quotas available by request. A higher portal quota does not itself
  override the licence limit.
- [Developer guidelines](https://content.tfl.gov.uk/syndication-developer-guidelines.pdf):
  documents legacy XML-feed freshness/display rules. Its illustrative two-minute
  values are not assumed to be Unified API retention rules; applicability to future
  JSON publication must be reviewed.

No specific raw-storage duration was identified in the reviewed licence. Before
qualification/use, the owner must review applicable feed guidance and the licence's
registration applicability (the licence states it applies from registration, while
the portal supports anonymous access), as well as display/attribution obligations.
Anonymous technical access is not treated as automatic legal approval. No provider
permission or prohibition is invented for the 72-hour product target.

Future credentials must remain external acquisition configuration, never in the
canonical URL, database fixtures, migrations or Git. No credential plumbing is added;
foundation tests need no key and remain entirely offline on isolated PostgreSQL.
