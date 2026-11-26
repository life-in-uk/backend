# Stock index ingestion

Status: implemented behind defaults that are all **off**. Design review 2026-11-20, owner-approved phase 1.
Phase 1 is the backend API. Phase 2 (owner decision 2026-11-20: keep the local-PC push design) adds the nginx route
in this repository, the collector's outbox and sender in the Stock Index Collector repository, and a CloudFront
behaviour that is applied only with owner approval (see "Phase 2: collector route"). Public display needs a
market-data display licence first.

The external Stock Index Collector runs on the owner's PC (WSL2, Python, SQLite, systemd, 120 s interval). After
each collection it pushes one batch of up to six observations to the backend. The backend stores them in
PostgreSQL and serves the latest values read-only.

| Symbol | Instrument | Market calendar | Change basis |
| --- | --- | --- | --- |
| `UKX` | FTSE 100 | LSE, Europe/London | Previous close |
| `SPX` | S&P 500 | NYSE, America/New_York | Previous close |
| `IXIC` | Nasdaq Composite | Nasdaq, America/New_York | Previous close |
| `DJI` | Dow Jones Industrial Average | NYSE, America/New_York | Previous close |
| `BTCUSD` | Bitcoin, USD | `CRYPTO_24_7`: always open | Rolling 24 hours (V18, see "Crypto") |
| `ETHUSD` | Ethereum, USD | `CRYPTO_24_7`: always open | Rolling 24 hours (V18, see "Crypto") |

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
- `previousClose` is for the stock indices only. `BTCUSD` and `ETHUSD` instead take the optional pair
  `reference24h` (decimal string, > 0) and `reference24hAt`, which must be **exactly** `observedAt` minus 24 hours.
  Both or neither; anything else is `400 MARKET_BATCH_INVALID`.
- Timestamps: ISO 8601 with `Z` or an explicit offset, at most microsecond precision.
- 1–6 observations, each symbol at most once.

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

Each new batch is one transaction, and every observation in it gets exactly one result:

| Result | Meaning |
| --- | --- |
| `STORED` | A new instant for that symbol and source. |
| `DUPLICATE` | The same values were stored for that instant before (normal while a market is closed). |
| `CORRECTED` | (V18) Different values for a stored instant, from a newer batch: applied, and the replaced values kept as a revision. See "Corrections". |
| `SUPERSEDED` | (V18) Different values for a stored instant, from a batch older than the one that set them: not applied. |

A whole batch is still refused only for authentication, authorization, structure, source or time-window errors
(the table below). Before V18 a differing value rejected the whole batch with 409 `OBSERVATION_CONFLICT`; V18 never
returns that code.

| Status | Code | Collector action |
| --- | --- | --- |
| 400 | `MARKET_BATCH_INVALID` (message names the field) | Permanent: mark rejected |
| 401 | `COLLECTOR_UNAUTHORIZED` | Pause sending, keep outbox, alert |
| 403 | `SOURCE_NOT_AUTHORIZED` (the key is not bound to this source) | Permanent: configuration error, alert |
| 409 | `BATCH_ID_CONFLICT` (nothing stored); before V18 also `OBSERVATION_CONFLICT` | Permanent: mark rejected, alert |
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

Optional (V18) `instruments`: the collector's per-pair crypto health, sent only when the collector has crypto on.
The top-level fields above keep describing the four stock indices only, exactly as before; a heartbeat without
`instruments` is the four-index heartbeat every version accepts.

```json
"instruments": [
  { "symbol": "BTCUSD", "status": "FAILED", "lastAttemptAt": "2026-11-20T14:32:05Z",
    "lastSuccessfulCollectionAt": "2026-11-20T14:24:05Z", "consecutiveFailures": 4, "failureCode": "UPSTREAM_TIMEOUT" },
  { "symbol": "ETHUSD", "status": "OK", "lastAttemptAt": "2026-11-20T14:32:05Z",
    "lastSuccessfulCollectionAt": "2026-11-20T14:32:05Z", "consecutiveFailures": 0 }
]
```

Only `BTCUSD` and `ETHUSD`, each at most once. `OK` means the pair's last attempt succeeded: `consecutiveFailures`
0, no `failureCode`, `lastSuccessfulCollectionAt` at or after `lastAttemptAt`. `FAILED` means it did not:
`consecutiveFailures` at least 1, a `failureCode`, and `lastSuccessfulCollectionAt` absent (never collected) or
earlier than `lastAttemptAt`. Times may not be later than `sentAt`. When the report is applied (newest `sentAt`), the
source's rows in `market_index_instrument_health` are replaced by the list; a heartbeat without it removes them. The
backend logs a warning when a pair reports its first failure in a row, then every 30th (about hourly).

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

Every instrument also has `assetClass` (`INDEX` or `CRYPTO`), `currency` (`null` for index points, `USD` for
crypto), `changeBasis` (`PREVIOUS_CLOSE` or `ROLLING_24H`), and for crypto `reference24h` and `reference24hAt`. A
crypto `change`/`changePercent` is against `reference24h`, and both are `null` when the collector had no verified
24-hour window: `null` means "no 24-hour change available", never "unchanged". Crypto `previousClose` is always
`null`, and its `market` is `{"exchange": "CRYPTO_24_7", "timeZone": "UTC", "session": "OPEN",
"calendarCoveredThrough": null}`.

`collector.instruments` (V18) monitors BTC/USD and ETH/USD independently of the indices and of each other. It
never changes `collector.status`, `collector.sources` or an index's `collectorStatus`, which keep their stock-index
meaning:

```json
"instruments": [
  { "symbol": "BTCUSD", "source": "sina", "status": "PROVIDER_FAILED", "consecutiveFailures": 4,
    "failureCode": "UPSTREAM_TIMEOUT", "lastAttemptAt": "2026-11-20T14:32:05Z",
    "lastSuccessfulCollectionAt": "2026-11-20T14:24:05Z", "freshness": "DELAYED" },
  { "symbol": "ETHUSD", "source": "sina", "status": "HEALTHY", "consecutiveFailures": 0, "failureCode": null,
    "lastAttemptAt": "2026-11-20T14:32:05Z", "lastSuccessfulCollectionAt": "2026-11-20T14:32:05Z", "freshness": "FRESH" }
]
```

`status` is the pair's own collector state, and also its `collectorStatus` in `indices`: `DISCONNECTED` or
`UNKNOWN` when the source is (the PC or collector is down); `UNKNOWN` when the source's newest heartbeat has no
health for the pair (crypto off, or a collector before this change); `NO_SUCCESSFUL_COLLECTION` (never collected);
`PROVIDER_FAILED` (last attempt failed; `consecutiveFailures` and `failureCode` say how long and why);
`COLLECTION_STALLED` (the pair's last attempt is older than `heartbeat-timeout` relative to the heartbeat's
`sentAt`); otherwise `HEALTHY`. A success resets `consecutiveFailures` to 0 (recovery). `freshness` is the pair's
value freshness: `STALE` when its newest value is older than `stale-after`, `UNAVAILABLE` when there is none. The
source is that of the newest value, else the first public source reporting the pair.

All six symbols are always present, in the fixed order (`UKX`, `SPX`, `IXIC`, `DJI`, `BTCUSD`, `ETHUSD`; the
array keeps its name `indices`). A symbol without data has `null` values and status `UNAVAILABLE`. Only `MARKET_INDICES_PUBLIC_SOURCES` are read; startup fails if `mock` is listed there. History is
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

## Corrections (V18)

**Incident (2026-11-20 to 2026-11-26).** Sina revises values after first publishing them: index closes settle in the
minutes after the close (SPX 2026-11-20 21:00: 6910.79, 6910.54, 6910.79, then 6910.39 for good, which is also the
next day's previous close), the FTSE 100's last minute is replaced by the official closing price at the next
morning's pre-open (2026-11-23 16:30: 10480.82 then 10486.35), and a forming minute differs from its final value.
The collector repeats the newest value of a closed market every cycle. Under V17 any differing value rejected the
whole batch (409 `OBSERVATION_CONFLICT`), and the collector marks such a batch rejected for good, so one revised
close discarded every later batch until a new minute replaced it. In production 3,320 batches were rejected; 751
observations (734 FTSE 100 minutes from 2026-11-23 08:15 onwards) never arrived, and the FTSE 100 was STALE through
the trading day of 2026-11-26 because the US closes of 2026-11-25 (Thanksgiving) were revised.

**Policy.** For an existing (symbol, source, `observedAt`) the values from the most recent collection win:

- Precedence is the pair (`collectedAt`, `batchId`) of the batch that carries the values: a later `collectedAt`
  wins; at the same `collectedAt` the greater `batchId` (canonical lowercase text) wins. The outcome therefore
  depends only on the batches, never on the order in which they arrive.
- A newer batch with different values corrects the row in place (`CORRECTED`): value, previous close, 24-hour
  reference, `observedAtSource`, `batch_id` (so `provenance.collectedAt` is that batch's) and `received_at`. The
  replaced values, both batch ids and both collection times are inserted into `market_index_observation_revision`
  (insert-only; a `CHECK` enforces that the correcting batch is newer). Revisions are deleted with their
  observation by retention.
- An older batch with different values changes nothing (`SUPERSEDED`), so a late outbox retry, a re-sent rejected
  batch or a replay can never overwrite a newer correction. An identical retry of a committed `batchId` stays a
  replay (`ALREADY_ACCEPTED`, nothing re-applied); a different body under that id stays 409 `BATCH_ID_CONFLICT`.
- A correction passes exactly the same validation as a new observation (structure, mock time source, crypto
  24-hour window, backfill window). Each instrument is decided on its own, so a correction to one never affects
  another; the batch stays all-or-nothing only for validation errors.
- Concurrency: the existing row is locked (`SELECT ... FOR UPDATE`) before the precedence check, and rows are taken
  in a fixed (symbol, `observedAt`) order, so concurrent batches serialise per row and cannot deadlock.
- A value that flips and flips back is applied each time, each audited (observed: at most four flips in the ten
  minutes after a close, then stable). A collector clock stepping backwards can make a newer value lose
  (`SUPERSEDED`) until a later cycle; collector times are bounded by `collector-clock-skew`.

The collector needs no change: it treats any 200 acknowledgement of its batch as sent and ignores the per-observation
results.

**Verification against production data.** All 3,823 outbox batches of the production collector (a read-only copy
taken 2026-11-26 16:27 UTC, 494 sent and 3,329 rejected, in their original order and with their original bodies)
were sent to this branch on a fresh database: every `sina` batch answered 200 (the 20 `mock` burn-in batches 422,
as the test server accepts only `sina`), with 1,664 `STORED`, 13,497 `DUPLICATE` and 51 `CORRECTED` results over 30
instants, and every stored value equals the most recently collected one (DJI 2026-11-25 21:00 = 50182.9492, SPX
2026-11-20 21:00 = 6910.3901, UKX 2026-11-25 16:30 = 10433.66).

## Crypto: BTC/USD and ETH/USD (V18)

Owner decision 2026-11-21: Sina Finance crypto minute bars, through the same collector, outbox, batch, endpoint,
tables and public read as the indices (one collector, one outbox, one upload pipeline, six instruments). The owner
accepted the licensing and redistribution risk of that source; Sina has not granted permission.

- **Market semantics.** Crypto trades continuously: `MarketCalendar.Exchange.CRYPTO_24_7` is always `OPEN` with
  no session start, so freshness is the observation's age alone (FRESH within `fresh-within`, then DELAYED, STALE
  after `stale-after`; a `COLLECTOR` time is never FRESH). No exchange session, weekend or holiday rule applies.
  The stock indices keep their calendars unchanged.
- **Change.** A rolling 24-hour change: the price at `observedAt` against `reference24h`, the price exactly 24
  hours earlier. The collector derives both from continuous Sina minute bars and sends the reference only when every
  minute of the window is present (Stock Index Collector `sina_crypto.py`). The backend stores what it receives and
  enforces, in the payload parser and a V18 `CHECK`, that `reference24hAt = observedAt - 24 hours`, that crypto rows
  never carry `previous_close` and index rows never carry a reference.
- **Idempotency.** A crypto line in the canonical request hash adds its reference. Index lines hash exactly as
  under V17, so replays of earlier stored batches still match.
- **Retention: unchanged (decision).** The same `retention` (P730D) and `max-backfill` (P30D) apply. Crypto adds
  one row per symbol per 2-minute cycle (about 1,440 rows a day, 1.05 M over two years for one source), well
  within the measured volume above (4.2 M rows: latest-value lookup 0.066 ms, retention 6 ms per 1,000 rows) and
  within retention's 20,000 rows per hourly run. The latest-value query is per symbol and source, so crypto rows
  never slow an index lookup. A separate retention would add configuration for no measured need.
- **Caching: unchanged (decision).** `GET /api/market-indices/latest` stays `Cache-Control: no-store`, and
  CloudFront forwards it uncached: freshness and `ageSeconds` are computed per request, and a crypto value changes
  every cycle. The collector already limits upstream load (two small requests per cycle once its window is warm).

## Release: V17 to V18

V18 widens two `CHECK`s (symbols; up to 6 observations per batch), adds two nullable columns and one `CHECK`, and
creates `market_index_instrument_health` (per-pair crypto health) and `market_index_observation_revision`
(correction audit), both new tables. From V18 an observation row can be updated in place by a correction (see
"Corrections"). Existing rows are unchanged and all
satisfy the new constraints. Release with `approve_migrations=18`. The previous image
(V17 code) is compatible with a V18 database: it reads only its four symbols by name, ignores the new columns and
validates the newer schema under Flyway's default `ignoreMigrationPatterns` (`*:future`). Rollback is therefore an
image rollback only. Crypto rows then stay stored but unused.

Rehearsed on 2026-11-23 against a disposable PostgreSQL 16 cluster with both jars, public read enabled:

| Step | Image | Result |
| --- | --- | --- |
| 1 | Previous (`main` 18a2fcd) on an empty database | Migrated V1–V17; health UP; Guides 200; `latest` 200. A UKX row was then added |
| 2 | This branch on the V17 database | "Migrating schema to version 18"; applied in 6 ms; health UP. A BTCUSD row with its 24-hour reference was then added |
| 3 | Previous image again on the V18 database (rollback) | "Schema is up to date"; health UP; Guides 200; `latest` lists exactly the four indices (UKX value unchanged), the crypto row ignored |
| 4 | This branch again | `latest` lists six; BTCUSD `ROLLING_24H`, `changePercent` -6.31, FRESH; ETHUSD `UNAVAILABLE` |

Repeated on 2026-11-26 with per-pair crypto health (`market_index_instrument_health`), fresh PostgreSQL 16 cluster:

| Step | Image | Result |
| --- | --- | --- |
| 1 | Previous (`main` 18a2fcd) on an empty database | V1–V17; health UP; Guides 200 |
| 2 | Previous on V17, traffic | Four-index batch 200 STORED; four-index heartbeat 200; six-instrument batch 400 `MARKET_BATCH_INVALID`; heartbeat with `instruments` 400 `HEARTBEAT_INVALID` (why the collector goes last) |
| 3 | This branch on the V17 database | V18 applied in 14 ms; four-index batch and heartbeat 200; six-instrument batch 200 (6 STORED); heartbeat with `instruments` 200; `latest`: indices HEALTHY, BTCUSD `PROVIDER_FAILED` (3 failures), ETHUSD HEALTHY, `collector.status` HEALTHY |
| 4 | Previous image on the V18 database (rollback) | "Schema is up to date" (only the expected Flyway "newer than the latest available migration" warning); health UP; Guides 200; four-index batch and heartbeat 200; crypto batch and `instruments` heartbeat 400; `latest` lists exactly the four indices; health rows untouched |
| 5 | This branch again (roll-forward) | Before any new heartbeat both pairs `UNKNOWN` (the rows predate the source's newest heartbeat, so they are not shown as current); after one heartbeat with `instruments`, BTCUSD `PROVIDER_FAILED`, ETHUSD HEALTHY |
| 6 | This branch with the real collector (mock provider, crypto on, HTTP) | Four cycles, BTC/USD failing in cycles 2–3: every batch and heartbeat 200; heartbeat row `OK` throughout; BTCUSD rows `OK:0`, `FAILED:1`, `FAILED:2`, `OK:0`; ETHUSD `OK:0` throughout; one backend warning at the first failure |
| 7 | This branch, the production collector's whole outbox replayed (see "Corrections") | 3,329 previously rejected batches all 200; 51 corrections; every value equals the newest collected one |
| 8 | Previous image on that corrected V18 database (rollback) | Health UP; Guides 200; `latest` serves the corrected values; a differing value is 409 `OBSERVATION_CONFLICT` again (V17 behaviour); revision table ignored |
| 9 | This branch again (roll-forward) | The same request: 200, DJI `CORRECTED`, a new UKX minute `STORED` |

Enablement order (each step is safe on its own and has its own approval):

1. **Frontend first.** A V18 backend always lists six instruments; the homepage before the crypto change accepts
   exactly four and would fall back to its placeholders, hiding the indices too. The new homepage accepts four or
   six (crypto rows then show as unavailable).
2. **Backend** with `approve_migrations=18`. The current collector keeps sending four-index batches, which V18
   accepts unchanged; BTCUSD and ETHUSD are listed as `UNAVAILABLE`.
3. **Collector** upgrade (SQLite schema 3 to 4, automatic), then `crypto_enabled: true` and a restart. Never before
   step 2: a V17 backend rejects a batch containing crypto as `MARKET_BATCH_INVALID`, discarding that cycle's
   indices as well. The first cycle sends prices without a 24-hour change; the next completes the window. With
   crypto on, heartbeats also carry `instruments`, which only V18 accepts.

Rollback after step 3 runs in reverse: set `crypto_enabled: false` on the collector and restart it first (its
batches and heartbeats are then exactly the four-index ones a V17 image accepts; its SQLite schema 4 stays), then
roll back the backend image. Rolling back the backend image while crypto is on would make the previous image reject
every batch (`MARKET_BATCH_INVALID`, indices included) and every heartbeat (`HEARTBEAT_INVALID`) until crypto is
switched off; pending batches stay in the collector outbox, but batches already rejected are not retried.
After any backend image rollback the previous image also brings back the whole-batch 409 `OBSERVATION_CONFLICT` for
a revised value (the incident in "Corrections"); corrected rows and revisions stay valid and readable.

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
  `X-Market-Collector-Token`, `Content-Type` and `CloudFront-Viewer-Address`, with no cookies and no query strings.
  `Content-Type` is listed explicitly because the app answers 415 to anything but `application/json`. The shared
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
  "HeadersConfig":{"HeaderBehavior":"whitelist","Headers":{"Quantity":3,"Items":["X-Market-Collector-Token","Content-Type","CloudFront-Viewer-Address"]}},
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
   /api/internal/market-indices/heartbeat` answers 403 `ORIGIN_FORBIDDEN` (before the recreate it answered 405
   from the app, which nginx still passed through under `/api/`).
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
