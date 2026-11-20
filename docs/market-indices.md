# Stock index ingestion

Status: implemented behind defaults that are all **off**. Design review 2026-11-20, owner-approved phase 1.
Phase 1 is the backend API. Phase 2 (owner decision 2026-11-20: keep the local-PC push design) adds the nginx route
in this repository, the collector's outbox and sender in the Stock Index Collector repository, and a CloudFront
behaviour that is applied only with owner approval (see "Phase 2: collector route"). Public display needs a
market-data display licence first.

The external Stock Index Collector runs on the owner's PC (WSL2, Python, SQLite, systemd, 120 s interval). After
each collection it pushes one batch of up to four observations to the backend. The backend stores them in
PostgreSQL and serves the latest values read-only.

| Symbol | Index | Exchange calendar |
| --- | --- | --- |
| `UKX` | FTSE 100 | LSE, Europe/London |
| `SPX` | S&P 500 | NYSE, America/New_York |
| `IXIC` | Nasdaq Composite | Nasdaq, America/New_York |
| `DJI` | Dow Jones Industrial Average | NYSE, America/New_York |

## Timestamp provenance

Every stored value carries four distinct times. None of them is ever substituted for another.

| Field | Meaning | Set by |
| --- | --- | --- |
| `observedAt` | The upstream time the value applies to | Collector, from the provider |
| `observedAtSource` | `EXCHANGE` (index calculation time), `PROVIDER` (provider quote time) or `COLLECTOR` (no upstream time, so the collector's fetch time) | Collector, declared per observation |
| `collectedAt` | The collector's local collection time (per batch) | Collector |
| `receivedAt` | Server receipt time | Database `clock_timestamp()` |

Rules:

- `observedAt` is stored unchanged, apart from normalisation to UTC. The server never fills it in from another
  time.
- Freshness is calculated only from `observedAt`. `receivedAt` is used only for audit and for collector liveness
  (heartbeat).
- `COLLECTOR` times are never shown as exchange times and are never rated `FRESH`. Source `mock` must declare
  `COLLECTOR`; a database `CHECK` enforces this as well.
- The latest value is the greatest `observedAt` per symbol among the public sources. Late, retried or out-of-order
  deliveries are stored as history and can never become the latest value.

## Refinement 1: collector heartbeat

A successful collection and a successful heartbeat are different events. The collector sends a heartbeat every
cycle, including when the provider fails, to `POST /api/internal/market-indices/heartbeat`. Heartbeats report the
present only. They are not queued for replay, and a `sentAt` more than ±5 min from server time is rejected. A
heartbeat never creates or changes an observation and never dates a value.

One row per source is kept (`market_index_collector_heartbeat`). Two parts of that row are updated differently:

- **Reported status fields** come from the newest report by the collector's own `sentAt`. An older report is
  acknowledged with `"applied": false`.
- **Liveness** (`received_at`) is refreshed by every authenticated heartbeat. A collector whose clock steps
  backwards, for example after an NTP correction, therefore never looks disconnected.

The request must be internally consistent, or it is rejected with 400; the database enforces the same rule with
`CHECK` constraints:

- `OK` means the last attempt succeeded, so `lastSuccessfulCollectionAt` must be present and at or after
  `lastAttemptAt`.
- `PROVIDER_FAILED` means the last attempt came after the last success, which may be absent.

Each public source gets its own state:

1. **`DISCONNECTED`**: no heartbeat received for longer than `heartbeat-timeout` (6 min). The PC is off or asleep,
   the network is down, or the collector has stopped. This compares server receipt time with the server clock.
2. **`NO_SUCCESSFUL_COLLECTION`**: a recent heartbeat, but no collection has ever succeeded.
3. **`PROVIDER_FAILED`**: a recent heartbeat, but the last collection failed (`providerFailureCode`).
4. **`COLLECTION_STALLED`**: heartbeats arrive, but the last attempt is older than `heartbeat-timeout` relative to
   the heartbeat's own `sentAt`.
5. **`HEALTHY`**: all the above are false. The last attempt was a successful collection. The value itself may be
   unchanged; each index's `freshness` says whether it is current.
6. **`UNKNOWN`**: no heartbeat has ever been received from that source.

Checks 2–4 compare collector-reported times only with each other, never with server time, so collector clock skew
cannot turn a failure into `HEALTHY`. `collector.status` is the **worst** state across all public sources,
ordered `DISCONNECTED` > `UNKNOWN` > `NO_SUCCESSFUL_COLLECTION` > `COLLECTION_STALLED` > `PROVIDER_FAILED` >
`HEALTHY`. One healthy source therefore never masks a failed, silent or unknown one. `collector.sources` lists each
source's state, and each index carries `collectorStatus` for the source its value came from.

## Refinement 2: bounded backfill and retention

| Setting | Default | Bounds | Effect |
| --- | --- | --- | --- |
| `MARKET_INDICES_MAX_BACKFILL` | `P30D` | `PT1H`–`P365D` | Observations older than this at receipt are rejected with `OBSERVATION_TOO_OLD`. This caps how far a collector outbox can backfill after an outage. |
| `MARKET_INDICES_RETENTION` | `P730D` | `P30D`–`P3650D`, and at least `max-backfill` + 1 day | Observations older than this are deleted, then batches no longer referenced. |
| `MARKET_INDICES_RETENTION_INTERVAL` | `PT1H` | `PT5M`–`PT24H` | Cadence of the background retention job. The first run is 10 min after startup. |

Retention runs on its own daemon thread (`MarketIndexRetention`), not on the ingestion path. It works without any
upload arriving, and an acknowledgement never waits for it. Each run deletes in autocommit chunks of 1,000 rows.
It stops after 20,000 rows or 60 seconds, and any remaining backlog is taken up by later runs. Failures are logged
and retried at the next run.

A batch row stays as long as any of its observations does, so an identical retry is still recognised as a replay
for the whole retention period. Because replays are checked before the time window (see the API section), a batch
retried after its observations crossed the backfill cutoff is still acknowledged with `"replay": true`. A new batch
with the same content is rejected with `OBSERVATION_TOO_OLD`.

Startup fails if a value is outside its bounds, or if retention does not exceed backfill. Accepted data can
therefore never be purged straight away. Retention never deletes the newest observation of any symbol and source,
so the latest value survives a long outage. History never overwrites anything: observations are insert-only, and
"latest" is chosen by `observedAt`.

## Refinement 3: market calendar, holidays and early closes

`MarketCalendar` transcribes each exchange's own published calendar. It is only trusted inside the window each
source has verified:

| Exchange | Coverage | Source (checked 2026-11-20) |
| --- | --- | --- |
| LSE | 2026-08-31 to 2027-12-31 | londonstockexchange.com/equities-trading/business-days. Half days: closing from 12:30 London. The page gives no regular hours, so the long-standing 08:00–16:30 is used. |
| NYSE | 2026-01-01 to 2027-12-31 | nyse.com/markets/hours-calendars. Core 09:30–16:00 ET; early close 13:00 ET. |
| Nasdaq | 2026-01-01 to 2026-12-31 | nasdaqtrader.com Trader.aspx?id=Calendar. Nasdaq has not yet published 2027, so IXIC is `MARKET_STATUS_UNKNOWN` from 2027 until this is updated. |

Each session is resolved in the exchange's own time zone. This handles the weeks when UK and US clocks change on
different dates. Outside coverage the session is `UNKNOWN`. The same applies when finding the previous close would
cross the edge of the coverage window.

| Freshness status | Rule |
| --- | --- |
| `FRESH` | Market time (`EXCHANGE`/`PROVIDER`), session known and `OPEN`, `observedAt` at or after this session's open, and age ≤ `fresh-within` (20 min) |
| `DELAYED` | Session `OPEN` and not fresh, with age ≤ `stale-after` (60 min). This includes the previous close shown just after the open. |
| `STALE` | Age > `stale-after`; or session `CLOSED` and `observedAt` earlier than the last scheduled close minus `close-tolerance` (30 min) |
| `MARKET_CLOSED` | Session `CLOSED` (weekend, holiday or after an early close) and `observedAt` within `close-tolerance` **either side** of the last scheduled close |
| `MARKET_STATUS_UNKNOWN` | Market time, but the session cannot be determined (outside calendar coverage); or session `CLOSED` and `observedAt` later than the last close plus `close-tolerance`, for example a weekend stamp or the regular close time on an early-close day, which that session cannot have produced |
| `MARKET_TIME_UNKNOWN` | `observedAt` is collector-derived (a collector time is never earlier than the real market time, so only an age above `stale-after` can be stated, as `STALE`); or `observedAt` is later than now |
| `UNAVAILABLE` | No observation from a public source |

**Future-dated observations.** Ingestion accepts `observedAt` at most `future-skew` (default 30 s, maximum 2 min)
ahead of server time, which allows for provider clock skew. Anything later is rejected with `FUTURE_OBSERVATION`.
The latest-value lookup only considers rows with `observed_at <= now`. A future-dated row therefore never displaces
the current quote before its time, and it is never rated `FRESH`.

Updating the calendar: transcribe the next published year from the three sources, extend coverage, and add test
rows to `MarketCalendarTest`. Never extend coverage without a source.

## API

### `POST /api/internal/market-indices/observations`

Header `X-Market-Collector-Token: <keyId>.<secret>`; `Content-Type: application/json`; body ≤ 4096 bytes; unknown
and duplicate fields are rejected.

```json
{
  "schemaVersion": 1,
  "batchId": "6f1c2a8e-4b8f-4d0e-9a51-2f4b7d1c9e03",
  "source": "provider-x-delayed",
  "collectedAt": "2026-11-20T14:32:05.214Z",
  "observations": [
    { "symbol": "UKX", "value": "8123.45", "previousClose": "8100.12",
      "observedAt": "2026-11-20T14:15:00Z", "observedAtSource": "PROVIDER" }
  ]
}
```

- `batchId`: a lowercase UUID, fixed by the collector when the batch is written to its outbox and reused on
  every retry.
- `source`: `^[a-z0-9][a-z0-9._-]{0,39}$`, which must be in `MARKET_INDICES_ACCEPTED_SOURCES`.
- `value` and `previousClose` (optional): decimal **strings** matching `^[0-9]{1,8}(\.[0-9]{1,4})?$` and > 0.
  NaN, Infinity, exponents and JSON numbers are rejected.
- Timestamps: ISO 8601 with `Z` or an explicit offset, at most microsecond precision.
- 1–4 observations, each symbol at most once.

Response `200` (also returned for an identical retry, with `"replay": true` and results `ALREADY_ACCEPTED`):

```json
{ "status": "ACCEPTED", "batchId": "6f1c2a8e-…", "replay": false,
  "observations": [ { "symbol": "UKX", "observedAt": "2026-11-20T14:15:00Z", "result": "STORED" } ] }
```

Checks run in this order:

1. Authentication (401/503).
2. Structure (400).
3. Source accepted by the server (422) and bound to the key (403).
4. Replay: an identical committed `batchId` is acknowledged with `"replay": true`, and a different body under the
   same id returns 409.
5. Only for a new batch: the time window (`COLLECTOR_CLOCK_SKEW`, `FUTURE_OBSERVATION`,
   `OBSERVATION_AFTER_COLLECTION`, `OBSERVATION_TOO_OLD`).

A replay is therefore never refused for having crossed the backfill cutoff, and never accepted without
authentication and source authorization.

`DUPLICATE` means an identical observation (same symbol, source, `observedAt` and values) came in an earlier
batch; this is normal while a market is closed. Each batch is one transaction: either every observation is
stored, or none is.

| Status | Code | Collector action |
| --- | --- | --- |
| 400 | `MARKET_BATCH_INVALID` (message names the field) | Permanent: mark rejected |
| 401 | `COLLECTOR_UNAUTHORIZED` | Pause sending, keep outbox, alert |
| 403 | `SOURCE_NOT_AUTHORIZED` (the key is not bound to this source) | Permanent: configuration error, alert |
| 409 | `BATCH_ID_CONFLICT`, `OBSERVATION_CONFLICT` (nothing stored) | Permanent: mark rejected, alert |
| 413 | `PAYLOAD_TOO_LARGE` | Permanent |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Permanent |
| 422 | `SOURCE_NOT_ACCEPTED`, `MOCK_TIME_SOURCE_INVALID`, `FUTURE_OBSERVATION`, `OBSERVATION_AFTER_COLLECTION`, `OBSERVATION_TOO_OLD` | Permanent |
| 422 | `COLLECTOR_CLOCK_SKEW` | Retry after the clock resyncs |
| 500 | `MARKET_INDICES_FAILED` | Retry with backoff (idempotent) |
| 503 | `MARKET_INGEST_DISABLED` (no key configured) | Retry with backoff |

### `POST /api/internal/market-indices/heartbeat`

Same header and limits.

```json
{ "schemaVersion": 1, "source": "provider-x-delayed", "sentAt": "2026-11-20T14:32:06Z",
  "collectorStatus": "PROVIDER_FAILED", "providerFailureCode": "UPSTREAM_TIMEOUT",
  "lastAttemptAt": "2026-11-20T14:32:05Z", "lastSuccessfulCollectionAt": "2026-11-20T14:30:05Z",
  "pendingBatches": 3, "oldestPendingCollectedAt": "2026-11-20T14:26:05Z" }
```

`providerFailureCode` (`^[A-Z][A-Z0-9_]{0,39}$`) is required exactly when `collectorStatus` is
`PROVIDER_FAILED`. `OK` requires `lastSuccessfulCollectionAt` at or after `lastAttemptAt`. `PROVIDER_FAILED`
requires `lastSuccessfulCollectionAt` to be absent or earlier than `lastAttemptAt`.

The response is `200 {"status":"ACCEPTED","applied":true}`. Errors:

- 400 `HEARTBEAT_INVALID`.
- 403 `SOURCE_NOT_AUTHORIZED`.
- 422 `SOURCE_NOT_ACCEPTED`, `HEARTBEAT_CLOCK_SKEW` or `HEARTBEAT_TIME_INVALID`.
- 401, 413, 415 and 503 as above.

### `GET /api/market-indices/latest`

Public, no parameters, `Cache-Control: no-store`. Until `MARKET_INDICES_PUBLIC_ENABLED=true`, it returns
`404 {"code":"MARKET_INDICES_DISABLED"}`.

```json
{
  "generatedAt": "2026-11-20T14:33:00.412Z",
  "collector": { "status": "PROVIDER_FAILED", "basis": "heartbeatReceivedAt", "heartbeatTimeoutSeconds": 360,
                 "sources": [ { "source": "provider-x-delayed", "status": "PROVIDER_FAILED" } ] },
  "indices": [
    { "symbol": "UKX", "name": "FTSE 100", "value": "8123.4500", "previousClose": "8100.1200",
      "change": "23.33", "changePercent": "0.29", "source": "provider-x-delayed", "collectorStatus": "PROVIDER_FAILED",
      "provenance": { "observedAt": "2026-11-20T14:09:00Z", "observedAtSource": "PROVIDER",
                      "collectedAt": "2026-11-20T14:32:05.214Z", "receivedAt": "2026-11-20T14:32:05.390Z" },
      "market": { "exchange": "LSE", "timeZone": "Europe/London", "session": "OPEN", "calendarCoveredThrough": "2027-12-31" },
      "freshness": { "status": "DELAYED", "ageSeconds": 1440, "basis": "observedAt",
                     "freshWithinSeconds": 1200, "staleAfterSeconds": 3600 } }
  ],
  "attribution": "…"
}
```

All four symbols are always present, in the fixed order. A symbol without data has `null` values and status
`UNAVAILABLE`. Only `MARKET_INDICES_PUBLIC_SOURCES` are read; startup fails if `mock` is listed there. History is
not public.

## Authentication

The collector credential is dedicated and separate from website sessions and every editorial credential. Startup
fails if its hash equals the hash of any editorial token or owner password.

- `MARKET_INDICES_COLLECTOR_KEYS=keyId:sha256hex:source[|source][,keyId:sha256hex:source[|source]]`, at most two
  keys. The server holds only hashes.
- **Source binding.** Every key names the sources it may write, and there is no wildcard. Observations and
  heartbeats for any other source are refused with 403 `SOURCE_NOT_AUTHORIZED`. This applies to replays too.
- Bind the mock collector's key to `mock` only and a licensed collector's key to its licensed source only. A
  mock-only credential can then never write, replay or heartbeat for a licensed (public) source.
- Every configured key is compared in constant time (`MessageDigest.isEqual`). The header is never logged or
  echoed.
- Rotation: add key B and recreate the app; switch the collector to B; confirm `credential_id` in
  `market_index_ingest_batch`; remove key A and recreate the app.
- Compromise: remove the key and recreate the app. The collector receives 401, pauses and keeps its outbox.

## Storage (V17)

- `market_index_ingest_batch`: one row per batch with its canonical request hash, for idempotency and conflict
  detection.
- `market_index_observation`: insert-only. `UNIQUE (symbol, source, observed_at)` provides idempotency and also
  serves the latest-value lookup.
- `market_index_collector_heartbeat`: one row per source, with status-consistency `CHECK`s.

Indexes and their uses:

| Index | Used by |
| --- | --- |
| `market_index_observation_natural (symbol, source, observed_at)` | Idempotency, conflict lookup, latest value (scalar `max()`), retention "newer row" probe |
| `market_index_observation_observed_at (observed_at)` | Retention scan, oldest first |
| `market_index_observation_batch (batch_id)` | Orphan-batch check |
| `market_index_ingest_batch_received_at (received_at)` | Orphan-batch scan |
| `market_index_ingest_batch_pkey (batch_id)` | Replay lookup and join |

### Query plans at representative volume

Measured 2026-11-20 on PostgreSQL 16 after `VACUUM ANALYZE`. The data was two years of 2-minute collections for two
sources: 4,210,560 observations (968 MB including indexes) and 1,052,640 batches. Times are `EXPLAIN ANALYZE`
execution times.

| Query | Plan | Time |
| --- | --- | --- |
| Latest value, current source | Index-only backward scan of the natural key for `max()`, then index probes | 0.066 ms |
| Latest value, public source with no rows | Natural-key index-only scan, 0 rows | 0.022 ms |
| Replay lookup by `batch_id` | Primary-key index scan | 0.019 ms |
| Retention: delete 1,000 expired observations | `observed_at` index scan (LIMIT) plus 1,000 natural-key index-only probes | 6.0 ms |
| Retention: delete 1,000 orphan batches | `received_at` index scan plus 1,000 `batch_id` index-only probes | 13.4 ms |
| Retention: nothing to delete | `observed_at` index scan, 0 rows | 0.008 ms |

**Rejected form of the latest-value query.** The obvious `… ORDER BY observed_at DESC LIMIT 1` was measured too.
For a source whose newest row is a year old, with statistics not yet updated, it was planned as a backward scan of
the `observed_at` index. It filtered 1,839,600 rows of other sources and took 159 ms, and the cost grows with the
whole table. The scalar `max()` form is always resolved on the natural-key index, so its cost is bounded by that
symbol and source's own rows.

## Release: V16 to V17 and previous-image compatibility

V17 only creates tables and indexes; it changes no existing object. Rehearsed on 2026-11-20 against a disposable
PostgreSQL 16 cluster, with the same Flyway behaviour as production (`ignoreMigrationPatterns` default `*:future`):

| Step | Image | Result |
| --- | --- | --- |
| 1 | Previous (`main` e4503f9) on an empty database | Migrated V1–V16; health UP; Guides 200; market routes 404 |
| 2 | This branch on the V16 database | "Migrating schema to version 17"; applied in 33 ms; health UP; `latest` 404 (disabled); collector POST 503 (no key) |
| 3 | Previous image again on the V17 database (rollback) | "Successfully validated 17 migrations"; "Schema is up to date"; health UP; Guides 200 |

Rollback is an image rollback only. The V17 tables stay, unused, and no database restore is needed. If V17 must
be removed after a rollback, drop the three `market_index_*` tables and the V17 history row, but only with owner
approval and after a backup.

## Phase 2: collector route

The collector posts to `https://lifeinuk.info/api/internal/market-indices/{observations,heartbeat}`. The request
passes CloudFront, then nginx, then the app. Each layer allows exactly these two paths.

**nginx** (`deploy/nginx/templates/lifeinuk.conf.template`, shipped with the deploy files; installed with
`deploy/ops/update-nginx-files.sh` and applied by recreating the proxy, see the enablement order below):

- Two exact locations, `POST` only. Any other method answers 403 `ORIGIN_FORBIDDEN`.
- Bodies of at most 4 KB. Larger bodies answer 413 `PAYLOAD_TOO_LARGE`.
- Their own per-viewer rate limit (`market_collector`, 60/min, burst 30).
- No location-level `proxy_set_header`, so the server-level lines still strip `Cookie`, `Authorization` and
  `X-Origin-Verify`. `X-Market-Collector-Token` passes through to the app, which checks it.
- Every other `/api/internal/` path answers 404. The origin secret check still applies first.
- `deploy/test/ci-stack-test.sh` checks all of this through nginx. With a CI-only key bound to `mock`, it also
  checks a stored batch, a replay, a 401 and a heartbeat, and that the public read stays disabled.

**CloudFront** (owner approval before it is applied; nothing here has been applied). The existing `/api/*`
behaviour allows GET/HEAD only. CloudFront itself refuses a POST there with 403 ("not configured to allow the HTTP
request method"). Add:

- A new origin request policy, `lifeinuk-market-collector`. It forwards only the headers
  `X-Market-Collector-Token` and `CloudFront-Viewer-Address`, with no cookies and no query strings. The shared
  `lifeinuk-api` policy stays unchanged, so the token header is never forwarded for public reads.
- A new cache behaviour, path pattern `/api/internal/market-indices/*`, ordered before `/api/*`:
  - same origin as `/api/*` (so the same `X-Origin-Verify` header and HTTPS-only connection)
  - viewer protocol `https-only`
  - all methods (CloudFront has no POST-only setting; nginx enforces POST)
  - managed cache policy `CachingDisabled`
  - the new origin request policy
  - the same viewer-request function and response headers policy as `/api/*`. The function passes every `/api/`
    request through unchanged.

```bash
DIST=E2VQEY0DI83CFH
aws cloudfront create-origin-request-policy --origin-request-policy-config '{"Name":"lifeinuk-market-collector",
  "Comment":"Stock index collector POSTs: token and viewer address only",
  "HeadersConfig":{"HeaderBehavior":"whitelist","Headers":{"Quantity":2,"Items":["X-Market-Collector-Token","CloudFront-Viewer-Address"]}},
  "CookiesConfig":{"CookieBehavior":"none"},"QueryStringsConfig":{"QueryStringBehavior":"none"}}' \
  --query OriginRequestPolicy.Id --output text > orp-market-id
aws cloudfront get-distribution-config --id $DIST > dist-before.json        # keep this file: it is the rollback
python3 - "$(cat orp-market-id)" <<'PY'
import json, sys
before = json.load(open("dist-before.json"))
config = before["DistributionConfig"]
json.dump(config, open("dist-rollback.json", "w"), indent=2)
behaviours = config["CacheBehaviors"]
api = next(b for b in behaviours["Items"] if b["PathPattern"] == "/api/*")
collector = dict(api, PathPattern="/api/internal/market-indices/*", ViewerProtocolPolicy="https-only", Compress=False,
    CachePolicyId="4135ea2d-6df8-44a3-9df3-4b5a84be39ad",   # managed CachingDisabled
    OriginRequestPolicyId=sys.argv[1],
    AllowedMethods={"Quantity": 7, "Items": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"],
                    "CachedMethods": {"Quantity": 2, "Items": ["GET", "HEAD"]}})
behaviours["Items"].insert(behaviours["Items"].index(api), collector)   # CloudFront matches in order
behaviours["Quantity"] = len(behaviours["Items"])
json.dump(config, open("dist-after.json", "w"), indent=2)
open("dist-etag", "w").write(before["ETag"])
PY
diff dist-rollback.json dist-after.json      # one added behaviour only
aws cloudfront update-distribution --id $DIST --if-match "$(cat dist-etag)" --distribution-config file://dist-after.json
aws cloudfront wait distribution-deployed --id $DIST
```

Check, with no collector key configured yet: `POST` with an empty JSON body to
`https://lifeinuk.info/api/internal/market-indices/heartbeat` answers 503 `MARKET_INGEST_DISABLED` from the app, and
`GET` on the same path answers 403.

Rollback: restore `dist-rollback.json` with the current ETag. Then delete the new origin request policy, which can
be deleted once no behaviour uses it.

```bash
aws cloudfront update-distribution --id $DIST --distribution-config file://dist-rollback.json \
  --if-match "$(aws cloudfront get-distribution-config --id $DIST --query ETag --output text)"
aws cloudfront wait distribution-deployed --id $DIST
```

**Collector side** (Stock Index Collector repository, README "Deliver to the Life in UK backend"):

- An outbox row is written in the same SQLite transaction as the snapshot. Retries reuse the same `batchId` and
  body, and a heartbeat is sent after every cycle.
- The credential is read from a mode-600 file.
- Mock data is always source `mock` with `COLLECTOR` times.
- Its `scripts/e2e_backend.py` runs the collector against this backend's jar and a disposable PostgreSQL, using
  mock data only.

**Production enablement order** (each step owner-approved):

1. Install the new nginx template on the server with `deploy/ops/update-nginx-files.sh`
   ([deploy-files-update.md](deploy-files-update.md#nginx-changes)). `update-deploy-files.sh` cannot be used: it
   refuses nginx changes. The release workflow refuses until the server digest matches the release commit, so
   this comes first. The script installs only the changed files in place and validates the template with
   `nginx -t` in a throwaway container. The running proxy keeps its old configuration.
2. Release the backend with `approve_migrations=17`. `release.sh` takes the pre-release backup and verifies its
   upload to S3 before changing anything. Ingestion and the public read stay off.
3. Recreate the proxy to apply the template. nginx renders templates only when its container starts, and
   `docker compose up -d` alone keeps the running container, because compose does not watch the content of a
   bind-mounted directory:

   ```bash
   bash $d/update-nginx-files.sh validate     # read-only nginx -t of the installed templates ($d: deploy-files-update.md)
   docker compose -f /opt/lifeinuk/compose.yml up -d --force-recreate --no-deps proxy
   ```

   The API answers 502 for a few seconds through CloudFront; static pages are unaffected. Then `GET
   /api/internal/market-indices/heartbeat` answers 403 `ORIGIN_FORBIDDEN` (it answered 404 before).
4. Apply the CloudFront change above.
5. The owner creates the collector token on the PC (collector README). Then enter `keyId:sha256:mock` with
   `set-env.sh … MARKET_INDICES_COLLECTOR_KEYS`, set `MARKET_INDICES_ACCEPTED_SOURCES=mock`, and recreate the app.
6. Set `backend_url` and `token_file` in the collector's `config.json` and restart its service. This is the private
   burn-in with mock data. The public read stays off, because `mock` can never be a public source.
7. Public display only with a licensed provider and source, `MARKET_INDICES_PUBLIC_SOURCES`, attribution and
   `MARKET_INDICES_PUBLIC_ENABLED=true`, in a separate approval.

## Operations

- Production stays fully off after deploying: no keys, no sources, public read disabled. Until the CloudFront
  behaviour exists, a POST from outside stops at CloudFront.
- Logs: one INFO line per batch (batchId, source, key id, stored/duplicate counts, oldest observation age), a WARN
  on a reported provider failure, and a WARN if retention fails. Tokens and bodies are never logged.
- Disable: unset `MARKET_INDICES_COLLECTOR_KEYS` (ingestion 503) and/or set `MARKET_INDICES_PUBLIC_ENABLED=false`
  (read 404), then recreate the app.
