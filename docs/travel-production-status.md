# Travel services in production: status and safe enablement

Checked 2026-11-18 against `main` 23ae920, the production image `lifeinuk-backend:57f25bf78ef9` and the public
API through CloudFront (read-only GETs). Provider approvals and licence notes:
[travel-provider-approvals.md](travel-provider-approvals.md); Rail:
[rail-production-readiness.md](rail-production-readiness.md).

## Status

| | Underground (TfL) | Roads (National Highways) | Rail (National Rail / RDM) |
|---|---|---|---|
| Code on `main` | yes | yes | yes |
| In the production image | yes (no app-code difference between 57f25bf and `main`) | yes | yes |
| Public endpoint | `GET /api/travel/underground` → **404 `UNDERGROUND_UNAVAILABLE`** | `GET /api/travel/roads?lat=&lon=` → **404 `ROADS_UNAVAILABLE`**; place search `GET /api/places/search` → **503 `PLACES_NOT_CONFIGURED`** | `GET /api/rail/stations/{crs}/arrivals\|disruptions` → **503 `RAIL_DISABLED`** |
| Data | none: no snapshot (the seed deliberately excluded travel data) | none | not fetched |
| Acquisition | scheduled refresh **off** (`TRAVEL_REFRESH_UNDERGROUND_ENABLED=false`) | refresh **off**; no `NATIONAL_HIGHWAYS_API_KEY`; no `OS_NAMES_API_KEY` | per-request only; **off** (`RAIL_ACCESS_ENABLED=false`), no RDM keys |
| Provider qualification in the production database | `tfl-underground-status` QUALIFIED (2026-10-03) | `national-highways-road-closures` QUALIFIED, scope amendment of 2026-11-11 present (version 2) | not a database source; gated by `RAIL_ACCESS_ENABLED` |
| Request limits in code | interval ≥ 1 min (default 10 min, 1 request per run); back-off, immediately on HTTP 429 | interval ≥ 5 min (default 10); ≤ 8 pages per run, ≥ 7 s apart; back-off, immediately on 429 | 6 per viewer and 60 per instance per minute, 4 concurrent; no retries |
| Retention | latest snapshot only, no raw evidence (no table growth) | same | nothing stored |
| Freshness | frontend shows status only if the snapshot is ≤ 15 min old | same (15 min) | each answer is a provider board, never cached (`no-store`) |

Everything returns its designed "unavailable" answer: nothing is broken, nothing is enabled. All three are off
by owner decision (2026-11-17, `deploy/env/backend.env.example`).

## Defect fixed here

**Rail's per-viewer limit was one shared limit in production.** `RailController` keyed the budget on
`request.getRemoteAddr()`. Behind CloudFront → nginx that is the nginx container for every viewer, and the
app does not use forwarded headers, so once Rail is enabled the 7th check per minute from *anyone* would
have answered 429 to *everyone*. The distribution's origin request policy (`lifeinuk-api`) forwards exactly
one viewer header, `CloudFront-Viewer-Address`, which nginx already uses for its own limits.
`RailClientAddress` now keys the budget on that address when the request comes from the private network
(nginx) and the header is well formed, and on the remote address otherwise. App-only change: no nginx,
Compose or CloudFront change, and the deploy-files digest is unchanged.

## Enabling safely (each step needs the owner's approval)

Common: values go in `/etc/lifeinuk/backend.env` (root 0600; keys from the password manager, never in Git,
logs or URLs), then `docker compose up -d app` recreates only the app (about a minute of API 502s; static
pages stay up). The instance already has outbound internet access (certbot, S3); no AWS change is needed.

**1. Underground (no key; lowest risk).**
1. Owner decision: continuous refresh of `https://api.tfl.gov.uk/Line/Mode/tube/Status` every 10 minutes
   (≈ 0.1 requests per minute against TfL's 500 per minute), latest snapshot only. Optionally record it as
   the cadence amendment proposed in travel-provider-approvals.md §2.3.
2. `TRAVEL_REFRESH_UNDERGROUND_ENABLED=true` → `docker compose up -d app`.
3. Check: `docker compose logs app | grep 'Underground refresh'` shows `succeeded`;
   `curl https://lifeinuk.info/api/travel/underground` returns 200 with an `observedAt` under 15 minutes old.
   Undo: set it back to `false` and recreate the app; the last snapshot then turns stale and the frontend
   stops showing it after 15 minutes.

**2. Roads (key needed).**
1. Owner re-reads the National Highways licence (it can change without notice, §1.3) and accepts the
   unconfirmed paragraph 21(e) reading (§1.4).
2. `NATIONAL_HIGHWAYS_API_KEY=<production subscription key>`, `TRAVEL_REFRESH_ROADS_ENABLED=true`.
3. Place search (optional; without it Roads works with the device location only): `OS_NAMES_API_KEY`
   after the owner confirms the OS Data Hub plan and its terms for public display. nginx already limits
   place search to 30 per viewer and 300 overall per minute.
4. `docker compose up -d app`; check the logs for `Roads refresh succeeded` and
   `/api/travel/roads?lat=51.5074&lon=-0.1278` returns 200 with a recent `snapshotAt`.

**3. Rail.** Approved for public release by the owner on 2026-11-18 (travel-provider-approvals.md §3); not yet deployed. Needs this fix
deployed, the Arrival and Departure keys, `NATIONAL_RAIL_DEPARTURES_BASE_URL` and `RAIL_ACCESS_ENABLED=true`;
the frontend now ships the Rail tool in production builds.

All three are to be released together: [travel-release.md](travel-release.md) (prepared, not executed).

**Release order.** This change needs a backend release (new image) through **Deploy production**. If PR #58
(deploy files) is merged first, the server's deploy files must be updated first (docs/deploy-files-update.md
on that branch), or every release is refused.
