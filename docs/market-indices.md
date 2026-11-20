# Stock index ingestion (phase 1)

Status: implemented behind defaults that are all **off**. Design review 2026-11-20, owner-approved phase 1.
Phase 1 is backend only. The collector, nginx and CloudFront changes are phase 2 and need separate approval.
Public display needs a market-data display licence first.

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

One row per source is kept (`market_index_collector_heartbeat`). The newest report by the collector's own `sentAt`
wins, so an out-of-order heartbeat is acknowledged with `"applied": false`. The public `collector.status` comes
from the most recently received heartbeat among the public sources:

| Status | Meaning |
| --- | --- |
| `HEALTHY` | Heartbeat received within `heartbeat-timeout` (6 min) and the last attempt succeeded. The collector is running even when the value is unchanged; whether the value is current is shown separately by each index's `freshness`. |
| `PROVIDER_FAILED` | Heartbeat recent, but the collector reports its last provider call failed (`providerFailureCode`). |
| `DISCONNECTED` | No heartbeat received for longer than `heartbeat-timeout`: the PC is off or asleep, the network is down, or the collector has stopped. |
| `UNKNOWN` | No heartbeat has ever been received from a public source. |

## Refinement 2: bounded backfill and retention

| Setting | Default | Bounds | Effect |
| --- | --- | --- | --- |
| `MARKET_INDICES_MAX_BACKFILL` | `P30D` | `PT1H`–`P365D` | Observations older than this at receipt are rejected with `OBSERVATION_TOO_OLD`. This caps how far a collector outbox can backfill after an outage. |
| `MARKET_INDICES_RETENTION` | `P730D` | `P30D`–`P3650D`, and at least `max-backfill` + 1 day | At most hourly, after a successful batch, up to 5,000 observations older than this are deleted, followed by batches no longer referenced. |

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
| `MARKET_CLOSED` | Session `CLOSED`, holiday or early close included, and `observedAt` within tolerance of the last scheduled close |
| `MARKET_STATUS_UNKNOWN` | Market time, but the session cannot be determined (outside calendar coverage) |
| `MARKET_TIME_UNKNOWN` | `observedAt` is collector-derived. A collector time is never earlier than the real market time, so only an age above `stale-after` can be stated, as `STALE`. |
| `UNAVAILABLE` | No observation from a public source |

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

`DUPLICATE` means an identical observation (same symbol, source, `observedAt` and values) came in an earlier
batch; this is normal while a market is closed. Each batch is one transaction: either every observation is
stored, or none is.

| Status | Code | Collector action |
| --- | --- | --- |
| 400 | `MARKET_BATCH_INVALID` (message names the field) | Permanent: mark rejected |
| 401 | `COLLECTOR_UNAUTHORIZED` | Pause sending, keep outbox, alert |
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
`PROVIDER_FAILED`. The response is `200 {"status":"ACCEPTED","applied":true}`. Errors are
`HEARTBEAT_INVALID` (400), `SOURCE_NOT_ACCEPTED`, `HEARTBEAT_CLOCK_SKEW` and `HEARTBEAT_TIME_INVALID` (422), plus
401, 413, 415 and 503 as above.

### `GET /api/market-indices/latest`

Public, no parameters, `Cache-Control: no-store`. Until `MARKET_INDICES_PUBLIC_ENABLED=true`, it returns
`404 {"code":"MARKET_INDICES_DISABLED"}`.

```json
{
  "generatedAt": "2026-11-20T14:33:00.412Z",
  "collector": { "status": "HEALTHY", "basis": "heartbeatReceivedAt", "heartbeatTimeoutSeconds": 360 },
  "indices": [
    { "symbol": "UKX", "name": "FTSE 100", "value": "8123.4500", "previousClose": "8100.1200",
      "change": "23.33", "changePercent": "0.29", "source": "provider-x-delayed",
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

- `MARKET_INDICES_COLLECTOR_KEYS=keyId:sha256hex[,keyId:sha256hex]`, at most two keys. The server holds only hashes.
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
- `market_index_collector_heartbeat`: one row per source.

The migration only adds tables. The previous image ignores them, and Flyway's default `*:future` pattern lets it
start against V17.

## Operations (phase 1)

- Production stays fully off after deploying phase 1: no keys, no sources, public read disabled.
- The collector route is not yet available at the edge. nginx `/api/` is GET-only and strips headers, and the
  CloudFront `/api/*` behaviour is GET/HEAD only. Phase 2 adds an exact-path nginx location and CloudFront
  behaviour (design review, "Edge changes"). Until then a POST from outside cannot reach the app.
- Logs: one INFO line per batch (batchId, source, key id, stored/duplicate counts, oldest observation age), a WARN
  on a reported provider failure, and a WARN if retention fails. Tokens and bodies are never logged.
- Disable: unset `MARKET_INDICES_COLLECTOR_KEYS` (ingestion 503) and/or set `MARKET_INDICES_PUBLIC_ENABLED=false`
  (read 404), then recreate the app.
