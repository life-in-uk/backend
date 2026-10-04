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

Issues #14/#16 add acquisition and interpretation; Issue #18 adds persisted
latest-known Current State below. **Change History, cleanup and scheduling are
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

## Controlled TfL Underground acquisition (Issue #14)

`TflUndergroundAcquisition.acquire()` is an internal, parameterless operation:
qualified canonical endpoint → one HTTP attempt → IngestionRun → immutable raw
EvidenceArtifact. It is not called at startup, scheduled, or exposed publicly.
`QualifiedSourceEndpoints.requireTflUnderground()` must succeed before any HTTP
invocation; `AcquisitionHistory.start()` rechecks eligibility in its short STARTED
transaction. Missing/ineligible configuration produces no run and no HTTP request.
There is no caller URL, alternate route, destination override or credential input.

The request URL comes from the qualified endpoint. The TfL transport wrapper
accepts only its exact canonical key/HTTPS URL. Bank Holidays keeps its own wrapper
and contract. Both use a package-private `JsonEvidenceHttp` primitive extracted
from the accepted Bank Holidays transport, sharing bounded body handling, header
validation, cancellation and deadlines. It is not a public fetch API or provider
framework, and restricts transport targets to the two approved endpoint URLs.
Acquisition orchestration stays explicit per provider; the existing evidence-owned
history operations already supply shared lifecycle/persistence behaviour.

The JDK client performs GET with a **5-second connection timeout** and **20-second
whole-response deadline** covering headers and body. Redirects are NEVER followed,
including cross-host redirects. There is no application retry/backoff. Requests
use `LifeInUK/0.0.1 (TfL Underground acquisition)`, accept `application/json`, and
request identity encoding. Access is anonymous: no key is mandatory, no optional
credential plumbing is added, and no credential appears in a stored URL.

Only HTTP **200**, one valid `application/json` Content-Type (charset/other valid
parameters allowed, header maximum 200 characters), identity/no encoding, and a
non-empty body are accepted. HTML, redirects, other statuses, encoded bodies and
missing/invalid media types fail. JSON syntax and TfL business fields are not
interpreted; the media type is preserved as received.

The response cap is **1 MiB (1,048,576 bytes)**. The Underground-only line-status
scope is small; this gives substantial headroom for nested disruption descriptions
while bounding storage and memory per invocation. Declared length can reject early,
and the body subscriber independently caps actual bytes, including chunked bodies.
Responses exactly at the cap are allowed. This reuses Bank Holidays' accepted cap
without changing either provider's retention policy.

Accepted body bytes are stored without decoding, parsing, trimming, decompression
or reserialization. `EvidenceArtifact.getByteSize()` derives the exact length from
its stored payload; PostgreSQL `octet_length(payload)` provides the same authoritative
size. No redundant size column or migration is needed. The existing constructor
hashes those exact bytes with SHA-256, and PostgreSQL independently verifies the
stored digest. Observation time is UTC `Instant.now()` after complete accepted body
receipt, within the run interval; it is not startup, migration or expiry time.

HTTP runs without a database transaction. Evidence insertion and SUCCESS completion
commit atomically through `AcquisitionHistory.succeed()`. HTTP/network/timeout/size/
media failures create coherent FAILED history with fixed bounded diagnostics and no
artifact. Evidence/SUCCESS persistence failures roll back together before a separate
FAILED transaction. If failure recording is unavailable, the exception propagates
and the already committed STARTED run remains for investigation; no terminal result
is invented. Each successful invocation creates one artifact, even for identical
payloads. Existing defensive copies, Hibernate immutability and database update/
delete protections apply equally to TfL evidence.

Automated tests use authored transport-only fixtures (not authoritative TfL schema
snapshots), a real loopback JDK HTTP server/client, and the accepted isolated
PostgreSQL process in a separate schema. A test-only client checks the original
canonical request before rerouting to loopback. It has no production equivalent.
Tests verify bytes, size, independent digest, provenance, deadlines, limits,
qualification-before-HTTP, redirects, rollback, immutability and Bank Holidays
configuration/evidence isolation. They require no internet or credential.

The separately owner-authorized Issue #14 live smoke succeeded with HTTP 200 and
one immutable 12,464-byte evidence artifact; independent byte length and SHA-256
verification matched. No further live request is part of interpretation.

**NOT YET IMPLEMENTED:** Change History, approximately 72-hour cleanup,
scheduler/polling and frontend. Issue #20 exposes stored Underground Current State
through the read API below. The future source-specific
retention policy remains intent only; no TTL, deletion or global evidence expiry
is introduced. A live smoke test requires separate explicit owner authorization.

## Deterministic Underground interpretation (Issue #16)

TfL → qualified acquisition → immutable EvidenceArtifact → deterministic
`TflUndergroundStatusParser` → in-memory `UndergroundStatusInterpretation`.
The parser reads only the supplied artifact, retaining its UUID and observation
Instant. It needs no Spring context, transaction, repository, acquisition or network.
Immutable source evidence remains unchanged and independently interpretable.

Each line retains its exact source ID/name and ordered list of operational statuses.
Each status retains the source integer severity, description and optional reason;
no severity remapping, primary/worst-status selection or text rewriting occurs.
Source line/status order and duplicate status entries are preserved. Duplicate line
IDs, duplicate JSON keys, trailing content and malformed required fields fail
explicitly; mode must be `tube` and scalar types are never coerced.

Unrelated provider metadata is tolerated and ignored: `$type`, provider timestamps,
disruptions, route/service/crowding structures and validity periods are not copied
into the domain model. Known fields remain strictly validated. Missing/null reason
means absence; a supplied reason string (including empty text) is preserved exactly.
Empty line/status arrays remain empty and imply no operating-state conclusion.

Interpretation remains in-memory. Issue #18 projects its supplied facts into
persisted Current State below. Meaningful Change History, approximately 72-hour
cleanup, polling/scheduler and frontend Travel integration remain
unimplemented.
Tests use authored offline fixtures and the existing isolated PostgreSQL harness;
they do not access development evidence or contact TfL.

## Persisted Underground Current State (Issue #18)

TfL → qualified acquisition → immutable evidence → deterministic interpretation
→ persisted latest-known Underground snapshot. `UndergroundCurrentStateProjector`
accepts only a supplied `UndergroundStatusInterpretation`; it neither parses raw
JSON, selects evidence, acquires data nor decides service freshness/importance.
The internal `UndergroundCurrentStates.current()` returns immutable persisted facts.

V4 adds one snapshot metadata row (created only on projection), lines keyed by
exact TfL line ID, and statuses keyed by line ID/source ordinal. Explicit line and
status ordinals preserve source order, including repeated status records. Display
text, source severity and optional reasons remain exact; absent and empty reasons
remain distinct. JDBC persistence uses the existing Spring/PostgreSQL stack.

A newer observation atomically replaces the complete snapshot; absent lines and
superseded statuses disappear. A newer empty interpretation replaces it with an
explicitly empty snapshot, without inventing an operating-state conclusion. Before
any projection, the read boundary returns absence, distinct from a projected empty
snapshot. Older observations return `IGNORED_OLDER` without writes. Equal time and
identical evidence/facts return `REPLAYED`; equal time with different evidence or
inconsistent facts returns `CONFLICT`, leaving state unchanged. A newer timestamp
reusing current evidence identity also conflicts. Conflicts require caller review;
there is no implicit tie-break, retry or acquisition.

First projection uses INSERT ON CONFLICT, followed by SELECT FOR UPDATE on the
singleton. Writers compare observation time only after obtaining that lock, and
hold it through replacement/commit. Projection owns one REQUIRES_NEW READ_COMMITTED
transaction; failures roll back metadata and all line/status changes together.
Internal reads use REQUIRES_NEW read-only REPEATABLE_READ so multi-query loading
cannot combine committed versions. No network occurs inside either boundary.

Provenance retains the scalar EvidenceArtifact UUID and exact observedAt. Time is
stored as UTC Instant epoch seconds plus nanoseconds to avoid PostgreSQL timestamp
rounding changing ordering/replay semantics. There is deliberately no foreign key
to raw evidence, so future evidence expiry cannot delete or block Current State.
Only superseded Current State rows are replaced; no evidence deletion, 72-hour
Current State TTL or history is introduced. Current State persists across restarts
until superseded by a successfully projected newer observation.

Offline tests use isolated PostgreSQL for faithful persistence, rollback, competing
writers, coherent reads, provenance, constraints and real application restart.
Meaningful Change History, raw-evidence cleanup, polling/scheduler and frontend
Travel integration remain NOT IMPLEMENTED. The Underground read API is documented below.

## Underground read API (Issue #20)

`GET /api/travel/underground` exposes persisted latest-known Underground Current
State through the accepted coherent `UndergroundCurrentStates` read boundary.
GET never acquires, parses or projects data and performs no semantic writes.

A stored snapshot returns HTTP 200 with this explicit contract:

```json
{
  "observedAt": "2026-10-03T15:47:08.441419123Z",
  "lines": [
    {
      "lineId": "central",
      "lineName": "Central",
      "statuses": [{"severity": 10, "description": "Good Service", "reason": null}]
    }
  ]
}
```

Line/status order, repeated statuses and source text are preserved. Absent reason
is JSON null; explicitly empty source reason stays `""`. `observedAt` is an ISO-8601
UTC Instant with the stored precision; no freshness classification is inferred.
Internal evidence UUIDs, hashes and persistence graphs are deliberately omitted.
Response construction needs no retained raw EvidenceArtifact.

No projected state returns HTTP 404 with code `UNDERGROUND_UNAVAILABLE`. A real
persisted empty snapshot returns HTTP 200 with its observedAt and `lines: []`.
Unexpected read failures return a bounded HTTP 500 `UNDERGROUND_READ_FAILED`
response without exception diagnostics. Errors contain only `code` and `message`.

This public facts endpoint follows existing unauthenticated API conventions.
No security, CORS or caching configuration is added; same-origin/frontend proxy
access remains the intended direction. Tests use offline fixtures and isolated
PostgreSQL. Automatic TfL polling, meaningful Change History, approximately
72-hour raw-evidence cleanup and frontend Underground integration remain
NOT IMPLEMENTED.

## Underground evidence projection (Issue #22)

`UndergroundEvidenceProjection.projectEvidence(evidenceArtifactId)` connects a
persisted EvidenceArtifact to Current State: evidence-owned
`UndergroundEvidence.successful(id)` → accepted `TflUndergroundStatusParser` →
accepted `UndergroundCurrentStateProjector`. It never contacts a provider.

Eligibility comes only from persisted provenance: the artifact's run must be
`SUCCESS`, its endpoint the canonical `tfl-underground-status` key/URL owned by the
canonical `transport-for-london` source and scope, and source/endpoint must still
be enabled and qualified. Missing, Bank Holidays, failed/started-run or
otherwise ineligible evidence returns `EVIDENCE_NOT_ELIGIBLE` before parsing.
Parser rejection returns `EVIDENCE_INVALID`; a projector/database failure returns
`PROJECTION_FAILED`. Otherwise the projector's own `APPLIED`, `REPLAYED`,
`IGNORED_OLDER` or `CONFLICT` outcome is returned unchanged. Every failure leaves
evidence and the previous Current State untouched.

`TflUndergroundAcquisition.acquire()` now returns `Result(runId, projection)`.
Projection runs only after the evidence-and-SUCCESS transaction has committed,
using the persisted artifact rather than response bytes. Failed acquisitions
have no projection. A projection failure does not change the SUCCESS run or its
evidence; it is reported only in `projection`. No transaction spans HTTP.

Explicit owner replay of exactly one persisted artifact, with no provider access:

```
java -jar target/backend-0.0.1-SNAPSHOT.jar --project-underground-evidence=<evidence-uuid>
```

Only that command-line option activates replay; ordinary startup does nothing.
Rejection fails startup with the bounded reason, and repeating a replay is safe
(`REPLAYED`). There is no replay HTTP endpoint, scheduler or migration. Automatic
TfL polling and raw-evidence cleanup remain NOT IMPLEMENTED.

## National Highways road closures source foundation (Issue #24)

National Highways is the third canonical provider: source key `national-highways`,
display name `National Highways`, scope `NATIONAL_HIGHWAYS_ROAD_CLOSURES`. One
endpoint belongs to it: `national-highways-road-closures`, the Road and Lane Closures
Data Service (DATEX II) v2.0 `GET /closures` at
`https://api.data.nationalhighways.co.uk/roads/v2.0/closures`. Planned and unplanned
closures are query modes of this one endpoint, not separate identities. The URL has
no query string or credential. No National Highways request was made.

V5 only widens V3's controlled CHECK constraints to admit this key/URL pair and bind
the new scope to the `national-highways` key. It seeds no rows and adds no credential
column. V3's immutable key/URL, scope and observed-ownership triggers still apply, so
existing TfL/Bank Holidays identities cannot be rewritten into the new pair.

`NationalHighwaysBootstrap` inserts missing canonical configuration only, PENDING by
default with an **unapproved draft** policy. `QualifiedSourceEndpoints
.requireNationalHighwaysRoadClosures()` and `requireQualified(id)` require enablement,
QUALIFIED status, a nonblank owner record and policy, and canonical ownership/scope/URL.
New explicitly qualified configuration needs all three properties, exactly as for TfL:

- `life-in-uk.source.national-highways.qualified=true`
- `life-in-uk.source.national-highways.qualification-record=<explicit owner decision>`
- `life-in-uk.source.national-highways.use-retention-policy=<owner-approved use/licence/retention decision>`

Existing records survive restart and conflicting bootstrap properties; later approval
uses `SourceEndpoint.qualify(record, policy)`. No approval is fabricated, and no
retention duration, polling frequency or attribution wording is chosen. The stored
attribution reference is the official Developer Portal licence agreement for National
Highways Transport Data Feeds (`https://developer.data.nationalhighways.co.uk/terms`);
the owner must review its attribution and other conditions before qualification.

External access configuration binds to `NationalHighwaysRoadsProperties`
(`life-in-uk.acquisition.national-highways`) from `NATIONAL_HIGHWAYS_ROADS_BASE_URL`
and `NATIONAL_HIGHWAYS_API_KEY`. The base URL is not a destination override: anything
other than the canonical v2.0 base (one trailing slash allowed) fails startup, so
configuration cannot redirect the key. The key is optional while no acquisition
exists, is never persisted, and is redacted from `toString()`. Later acquisition must
send it only as `Ocp-Apim-Subscription-Key`, with `X-Response-MediaType:
application/json` and `X-Data-Format: DATEXII` (recorded as constants). The existing
JSON transport still accepts only the Bank Holidays and TfL URLs.

Tests run offline on isolated PostgreSQL and never receive the developer's key; the
isolated initializer pins it blank. Acquisition, explicit `closureType` selection,
date-window semantics, `pageCursor`/`x-next` pagination, DATEX II parsing, Roads
Current State, Change History, cleanup, scheduling and any public Roads API remain
NOT IMPLEMENTED.

## National Highways unplanned road closures acquisition (Issue #26)

`NationalHighwaysRoadClosuresAcquisition.acquire()` is an internal, parameterless
operation acquiring **current unplanned** closures once, centrally, for all users. It
is never triggered by a user request, scheduled, retried or exposed publicly. Planned
closures and `modifiedSinceDateTime` are deliberately out of scope (owner decision).

Each run requires `QualifiedSourceEndpoints.requireNationalHighwaysRoadClosures()` and a
header-safe `NATIONAL_HIGHWAYS_API_KEY` before any IngestionRun or HTTP request exists.
Requests go only to the canonical `.../roads/v2.0/closures` over HTTPS through the shared
bounded `JsonEvidenceHttp` (no redirects, 1 MiB per response, deadlines covering headers
and body, exactly one `application/json` Content-Type, HTTP 200 only). Headers:
`Ocp-Apim-Subscription-Key` (the only place the key appears), `X-Response-MediaType:
application/json`, `X-Data-Format: DATEXII`, `Accept: application/json`. The key is never
placed in a URL, message, log or persisted value.

**Query window (owner V1 decision).** `closureType=unplanned`, `startDateTime =
acquisitionNow - 6 hours`, `endDateTime = acquisitionNow`, both derived from one captured
UTC instant and formatted `yyyy-MM-ddTHH:mm:ss` with no offset, per the official contract.
This follows the contract's documented worked-example shape; National Highways does not
guarantee these semantics. **Accepted V1 limitation:** if the provider filters unplanned
closures by start time, an incident that began more than six hours before acquisition but
is still active may be omitted. No larger undocumented lookback is invented.

**Pagination.** The first request has no `pageCursor`. Continuation is followed only from
the provider's `x-next` header, which must be a single HTTPS link to the canonical host and
path carrying exactly one URL-safe `pageCursor`/`PageCursor`; that cursor is appended to
the same window query. No/blank `x-next` ends the run. A repeated cursor fails as
`PAGINATION_LOOP`; malformed or foreign continuation as `PAGINATION_INVALID`; more than 8
pages as `PAGINATION_LIMIT` without making a ninth request. Pages are strictly sequential.

**Rate limit.** The provider allows 10 requests/minute per key. A process-wide pacer keeps
at least 7 seconds between requests, across pages and runs, so no rolling minute exceeds 9.
A maximal 8-page run takes under a minute, compatible with the intended (not implemented)
~10-minute cadence. HTTP 429 fails the run; there is no retry.

**Evidence and run semantics.** Every page is kept as its exact response bytes with SHA-256.
Pages are held in memory until the run finishes, then all artifacts and the SUCCESS
transition commit in one short transaction (`AcquisitionHistory.succeedWithResponses`). Any
failure on any page — HTTP, timeout, network, pagination, size or persistence — marks the
run FAILED with a bounded `Unplanned page N: ...` message and persists **no** evidence, so a
partial acquisition can never look like a complete snapshot. No transaction is open during
network I/O.

V6 adds generic, optional provenance beside the payload: `request_query` (the exact
credential-free query sent, including window and cursor), `page_number`, `http_status` and
`requested_at`. All four are present or all absent (existing evidence stays NULL); queries
resembling credentials are rejected; page positions are unique per run; V2's append-only
trigger makes them immutable. Payload JSON is never modified.

Tests use a loopback server behind the real JDK client, a fixed acquisition instant,
simulated pacing time and a fixture key. The separately owner-authorized Issue #26
live smoke succeeded with one HTTP 200 JSON page (101,448 bytes), verified SHA-256
and no continuation. No additional provider request is part of interpretation.
Roads Current State and the location-aware read API are described under Issue #30
below. Scheduling and planned closures remain NOT IMPLEMENTED.

## National Highways road-closure interpretation (Issue #28)

`NationalHighwaysRoadClosuresParser` interprets one supplied immutable evidence
page into in-memory `NationalHighwaysRoadClosuresInterpretation`. It validates the
successful run, canonical source/endpoint/scope, current qualified/enabled source
configuration and captured page provenance before reading JSON. Callers must
initialize the run/endpoint/source associations before detaching the artifact;
uninitialized provenance is rejected rather than causing implicit SQL. The parser
has no repository, acquisition, network or persistence operation.

The supported JSON envelope is `D2Payload` / `SituationPublication` with ordered
`situation` and `situationRecord` arrays. Situation and record `idG` values are
required. Source record `versionG`, lifecycle/header information, publication,
creation/version/validity times, management/cause codes, source identification and
public comments are retained when supplied. Provider timestamps require valid
offset-aware ISO date-times; no observation time replaces missing business times.
Malformed consumed fields, duplicate JSON keys and trailing documents fail.
Unrelated additional metadata is ignored; missing optional values remain absent.
Repeated situations, records, comments, locations and lanes are never deduplicated.

Supported observed location forms are complementary `locLinearLocation` and
`locSingleRoadLinearLocation`, plus ordered `locLocationGroupByList` members.
They retain GML line geometry, source position-list text, exact decimal coordinate
tuples, CRS name, location descriptions, carriageway/lane enums and extensions,
lane numbers/status/direction, restricted/operational lane counts, road name,
network reference/element identifiers, directions, height-grade codes and from/to
distance offsets. Geometry and groups remain structured, with immutable nested
collections. No geometry simplification, geocoding or relevance calculation occurs.

The locally inspected source literally used `ESPG::4326`. This spelling and the
ordinate order are preserved without correction or an assumed latitude/longitude
axis mapping. Only the observed two-dimensional GML line form is supported;
point locations, other coordinate dimensions/record variants and undocumented
location forms are not silently converted or discarded. Vehicle restrictions or
separate contraflow structures were not present in the inspected page and no
speculative model for them is introduced.

Each interpretation retains artifact UUID, logical IngestionRun UUID, page number,
requestedAt, observedAt and credential-free request query. Pages from one run can
therefore be grouped later; an individual page is not a complete source snapshot.
No aggregation or projector is implemented here. Interpretation adds no table,
migration or dependency. Deterministic synthetic fixtures and isolated PostgreSQL
tests verify facts, detached two-page provenance and unchanged evidence/source
history. The full production artifact is not a committed fixture.

## National Highways Roads Current State and location-aware API (Issue #30)

The implemented path is successful logical IngestionRun → all retained pages →
the #28 parser → durable normalized Roads Current State → location relevance →
`GET /api/travel/roads?lat=<latitude>&lon=<longitude>`. GET never acquires, parses
evidence, projects state or joins back to evidence. Latitude and longitude are
required, finite and within geographic ranges; missing/invalid input returns a
bounded 400, never a nationwide dump. No CORS, authentication or caching changes
are introduced.

One successful #26 run is one snapshot. The evidence-owned loader checks canonical
qualified National Highways provenance, all available pages numbered contiguously
from one (at most eight), and the same six-hour unplanned window. The first page
has no cursor; later pages have captured continuations. #26 commits all pages and
SUCCESS together, and existing evidence is append-only. Parsing every page must
succeed before replacement. Page, situation and record order, repeated records,
source text, lifecycle/time/road/lane facts and nested geometry remain preserved.

V7 adds only `roads_current_snapshot`: a singleton row containing scalar run and
endpoint UUIDs, run start time as epoch seconds/nanoseconds, projected time, page
count and JSONB **normalized facts**, not raw DATEX II. Run start time is the
ordering/acquisition timestamp; neither projection wall-clock time nor provider
record times decide which snapshot wins. There is no foreign key to raw evidence,
run or endpoint, so future raw retention cannot erase or block Current State.
Current State has no TTL. No cleanup is implemented. Future cleanup must consider
complete logical runs; replay requires the complete retained page set.

`INSERT ... ON CONFLICT DO NOTHING` followed by `SELECT ... FOR UPDATE` serializes
first and subsequent projectors. Comparison and complete replacement share one
transaction. Older snapshots are ignored; same-run/time/facts replay makes no
change; equal-time different runs or inconsistent same-run replay return CONFLICT.
A newer snapshot removes all absent records. A newer empty snapshot persists new
provenance with zero records, distinct from never-projected state. A failed
replacement rolls back. One SELECT reads all metadata/facts from a coherent
committed snapshot, including during a concurrent writer.

**Endpoint-specific coordinate decision.** The owner explicitly approved the
observed National Highways Road & Lane Closures two-dimensional GML representation
whose literal CRS is `ESPG::4326`: first ordinate = latitude, second = longitude.
The stored smoke example `(52.193516, -0.908380)` belongs to “M1 northbound between
J15 and J15A”. This mapping is specific to this endpoint and representation,
**not** inferred from a generic CRS identifier. #28 retains the original spelling,
ordinate ordering and decimal values unchanged. Other CRS labels, invalid
coordinates, missing geometry and ambiguous antipodal segments are not given
invented relevance. A record with no usable component is excluded.

**V1 relevance:** inclusive 15,000 metres, owned by the backend (no caller radius).
Distance is the minimum distance to any minor great-circle segment of any usable
polyline, including endpoints and all grouped locations, on a mean-radius sphere
(6,371,008.8 metres). This is straight-line proximity, not driving distance or route
planning. No vertex-only shortcut, description matching, geocoding, simplification
or inferred coordinates are used. Calculation uses double precision after the
source geometry has been preserved. Results sort by distance, situation ID, then
record ID; exact ties retain source order and duplicates.

The response is `{snapshotAt, relevanceRadiusMeters, disruptions: [...]}`. Each
disruption exposes provider situation/record ID and version, ordered descriptions,
type/cause/status, optional start/end times, `distanceMeters` and supported location
components with source descriptions, roads/directions and explicit latitude/
longitude coordinates. Missing optional values are null. Internal run/endpoint/
evidence IDs and raw payloads are not exposed. Source text is not rewritten.
Snapshot time is a UTC ISO Instant. Valid location with no relevant records or a
persisted empty snapshot returns 200 with `disruptions: []`; never-projected state
returns bounded 404 `ROADS_UNAVAILABLE`; unexpected failures return bounded 500
`ROADS_READ_FAILED` without diagnostics.

Owner-controlled offline replay uses the command-line option
`--project-roads-run=<successful-run-uuid>`, analogous to Underground replay. Normal
startup does nothing; the option invokes only persisted evidence lookup, #28 parsing
and projection. There is no public replay API or network dependency. If raw pages
are unavailable/invalid, replay fails rather than acquiring replacements. Existing
Current State continues to serve after raw evidence is removed.

Tests are offline on the isolated PostgreSQL harness and synthetic fixtures.
Current State is latest-known provider data, not a guarantee that every incident
still applies at query wall-clock time: the accepted #26 six-hour-window limitation
remains. No scheduler/polling, planned roadworks, Change History, cleanup, relevance
personalisation, routes, frontend, maps or AI is implemented.

## Roads current/stale semantics (Issue #32)

`GET /api/travel/roads` returns only disruptions that are current under the provider's own
validity semantics. Roads Current State still stores the complete projected snapshot
unchanged; `NationalHighwaysValidity` is applied at read time only, with an injectable clock.

- National Highways Road and Lane Closures v2 contract: `validityStatus` is the
  "Specification of validity, either explicitly overriding the validity time specification
  or confirming it" (`active | planned | suspended | definedByValidityTimeSpec`).
- DATEX II v3.4 Validity: `active` = "temporarily valid regardless of the validity time
  specification"; `suspended` = "temporarily invalid regardless of the validity time
  specification". DATEX II ValidityStatusEnum: `planned` = "currently planned regardless of
  the definition of the validity time specification"; `definedByValidityTimeSpec` = "in
  accordance with the definition of the validity time specification".

Therefore `suspended` and `planned` records are withheld whatever their times; `active`
records remain current after their `overallEndTime`; only `definedByValidityTimeSpec` is
evaluated against its inclusive overall start/end at read time. Absent or unrecognised status
is not treated as proof of non-currency. Provider values are matched exactly and never rewritten.
A record missing from a later complete snapshot disappears through whole-snapshot replacement.

No `endTime < now` rule is applied to `active` records: the retained smoke evidence contains
`active` records whose 15-minute validity windows ended hours before the snapshot, and
`suspended` records whose windows had not yet ended, so time alone does not decide currency.
Current State age (no scheduler exists yet) is a separate concern exposed via `snapshotAt`.

## Place search for Roads (Issue #32)

`GET /api/places/search?q=<postcode, town or place>` resolves a user-entered place so the frontend can
call `GET /api/travel/roads?lat=&lon=` with a chosen candidate. Browser geolocation stays optional. There is
no autocomplete in V1: one search per explicit submit. Roads itself is unchanged.

The backend calls the owner-approved **OS Names API** (`OS_NAMES_BASE_URL`, `OS_NAMES_API_KEY`); the browser
never does. Request: `GET https://api.os.uk/search/names/v1/find?query=<encoded>&maxresults=10&fq=<types>&format=JSON`
with the key only in the `key` header. `fq` restricts results to the documented `LOCAL_TYPE` values
`Postcode`, `City`, `Town`, `Village`, `Hamlet`, `Suburban_Area` and `Other_Settlement`. Roads, POIs,
landforms, addresses and UPRNs are out of scope, and any other returned type is dropped.

Response: `{query, places[], attribution}`. Each place has `id` (OS ID), `label`, `name`,
`type` (`postcode|city|town|village|hamlet|suburb|settlement`), `area`, `region`, `country`, `latitude`
and `longitude`. Provider order is kept and at most 10 places are returned. No match is a 200 with an empty list.
`attribution` is "Contains OS data © Crown copyright and database right <year>" (OS OpenData, OGL) and
should be displayed with results. Errors are bounded: 400 `PLACE_QUERY_INVALID` (blank, control characters, or
more than 100 characters), 503 `PLACES_NOT_CONFIGURED`, 502 `PLACES_UNAVAILABLE` (timeout, network, non-200)
and 502 `PLACES_UPSTREAM_INVALID` (non-JSON, oversized or malformed). All responses are `Cache-Control: no-store`.

**Coordinates.** OS Names returns British National Grid easting/northing (`GEOMETRY_X/Y`). `BritishNationalGrid`
converts them to WGS84 using OS's documented method: the exact inverse Transverse Mercator on Airy 1830,
followed by the OS-published 7-parameter Helmert OSGB36 → WGS84 shift and an exact geodetic conversion on GRS80.
It is tested against the OS worked example (Caister Water Tower), where the TM step matches to 0.00005″.
The Helmert step is OS-stated as accurate to about 3.5 m (95%); the measured residual against the ETRS89
reference is 3.57 m. OS's definitive OSTN15 grid (~0.1 m) needs an external grid file and is not used.
National Highways coordinate handling is unaffected.

**HTTP safety.** HTTPS to the pinned canonical base only (other base URLs fail startup), no redirects, 5 s
connect and 10 s total deadline covering headers and body, 512 KiB body cap, HTTP 200 only, exactly one JSON
Content-Type, identity encoding, and fixed error text (never the query, key, URL or provider body).

**Privacy.** Searches are geographic queries only. Queries and coordinates are not logged, persisted,
cached, profiled or associated with anyone, and they are sent only to OS Names. No geocoding cache exists;
OGL places no caching restriction on OS OpenData, but V1 needs none. Tests mock the provider and never
receive the real key.

## Public curated guides (Issue #34)

V8 adds `guide` and `guide_source`, mapped and validated by JPA. A guide has a unique
lower-case hyphenated slug, a generic category key in the same format, title,
summary and one Markdown `content` document. Source references contain organisation,
title, URL, UTC `accessedAt` and an explicit editorial order. No source classification
or article-section tables are needed. Reference URLs are metadata and are never
fetched or checked by guide reads.

Publication uses only `DRAFT` and `PUBLISHED`. New Guide objects are drafts;
`publish(Instant)` explicitly sets publication/update timestamps. The database
requires drafts to have no publication time and published guides to have one,
with `updatedAt >= publishedAt`. Status is the public boundary; timestamps do not
schedule publication. Timestamps use PostgreSQL timestamptz and public UTC ISO
Instants, with PostgreSQL microsecond precision.

`GET /api/guides` returns an array of `{slug, category, title, summary, publishedAt,
updatedAt}` for published guides, newest publication first then slug ascending.
The database query selects only these metadata fields, not Markdown or sources.
An empty list is 200 with `[]`. `GET /api/guides/{slug}` returns the same metadata
plus `content` and ordered `sources: [{organisation, title, url, accessedAt}]`.
DTO mapping finishes inside a read-only transaction; no persistence entity or
internal ID is serialized. Unknown and unpublished slugs both return identical
bounded 404 `GUIDE_NOT_FOUND` responses. Unexpected reads return bounded 500
`GUIDES_READ_FAILED`. No CORS, authentication or dependency change is introduced.

There is no production content seed or startup importer. Controlled test fixtures
construct a Guide, add its source references, explicitly publish when appropriate,
and save the aggregate through the package-private GuideRepository in a transaction.
For owner-controlled initial content insertion, the same V8 schema can be populated
with a small reviewed SQL transaction: insert a DRAFT guide with a fixed UUID and
slug, insert its sources with fixed UUIDs and zero-based `source_order`, then set
`status='PUBLISHED'`, `published_at` and `updated_at` together only after the owner
chooses publication. Slug uniqueness prevents accidental duplicate insertion. No
generic CMS import framework or public write endpoint is required.

Tests use synthetic documents and isolated PostgreSQL only. Markdown rendering,
Health & NHS articles, admin/editor APIs, editorial workflow, revisions, AI,
source monitoring, search and frontend work are not implemented.
