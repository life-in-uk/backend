# Rail production readiness review

Scope: isolated Rail branch, 27 October 2026. Repository inspection and local,
mocked tests only. No production host, reverse proxy, secrets, live RDM request,
licensing attachment or infrastructure was inspected or changed. Production
routing and effective environment values therefore remain unverified.

## Required configuration

| Spring environment variable | Required setting |
| --- | --- |
| `NATIONAL_RAIL_API_BASE_URL` | Arrival gateway; defaults to `https://api1.raildata.org.uk/1010-live-arrival-board-arr/LDBWS`. Preserve the verified working Arrival configuration. |
| `NATIONAL_RAIL_API_KEY` | Arrival product Consumer Key; empty by default, so arrivals fail closed with 503 when unconfigured. |
| `NATIONAL_RAIL_DEPARTURES_BASE_URL` | `https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS`; empty by default. |
| `NATIONAL_RAIL_DEPARTURES_API_KEY` | Separate Departure product Consumer Key; empty by default. Both Departure values must be supplied for departures. |

`application.yml`, `RailProperties`, `RailDepartureProperties` and `RailClient`
implement these bindings. Spring does not load `.env` automatically. Keep local
keys only in git-ignored `.env`; load them into the backend process as described
in the README. Production must inject protected backend environment settings
through the existing deployment mechanism, outside public files/source control.
Do not copy keys into the frontend, any `VITE_` setting, URLs, logs, screenshots,
PR descriptions or shell tracing. No Consumer Secret is used.

Both property records redact keys in `toString()`. Outbound requests use the
`x-apikey` header over HTTPS, disable redirects and omit credentials from URLs.
Errors use fixed public messages and never return provider bodies or keys. Avoid
HTTP wire/header logging and exposing environment/configuration management
endpoints in production. No production secret value was read for this review.

Rail adds no persistence or migration. Existing application database settings
(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) and other services remain prerequisites
of the existing application; Rail needs no new database or service.

## Same-origin integration and routing

The frontend uses relative `GET /api/rail/stations/{crs}/disruptions` URLs with
`direction=arrivals|departures`. The Arrival endpoint is also available at
`GET /api/rail/stations/{crs}/arrivals`. In development, `vite.config.ts` proxies
`/api/rail` to `BANK_HOLIDAYS_API_TARGET`, default `http://127.0.0.1:8080`.
That server-side setting is not shipped as a browser variable. The Vite proxy is
not production configuration.

The existing production reverse proxy must forward `/api/rail/` to the backend,
preserving the complete path and query. It must route these requests before SPA
fallback, preserve backend status and `Cache-Control: no-store`, and avoid caching
API errors/snapshots. Static `/rail/stations.csv`, `/rail/NOTICE.txt` and
`/rail/ODbL.txt` stay on the frontend origin. Ensure HTTPS externally and allow
backend egress to the two official gateways. No production proxy/deployment
configuration exists in these repositories, so actual routing cannot be confirmed
from this review. Do not introduce a new proxy/service to satisfy this checklist.

No Rail CORS override is present. Same-origin browser requests need no cross-origin
allowance; the dev proxy keeps the local integration same-origin too. Do not add
wildcard CORS as a substitute for correct routing. Deployment smoke checks should
confirm JSON, not SPA HTML, at the API URL in both directions, with no credentials
visible to the browser. The readiness tests use mocked providers, not live keys.

## Failure, timeout and request-volume behaviour

- `RailController`: validates CRS, directions and query bounds before requesting
  the provider; disruptions request nine rows. Responses, including errors, are
  `no-store`. Missing configuration maps to 503; authentication to 502; upstream
  429 to 503; timeout to 504; malformed/provider/network failures to bounded errors.
- `RailClient`: five-second connect timeout, ten-second request/wait timeout,
  one-MiB response limit, JSON-only and identity-encoding checks, no redirects or
  automatic retries. These bounds limit one request; they do not cap aggregate
  request volume. Keep the existing reverse proxy's timeout sufficient to pass
  backend timeout responses rather than rewriting them as frontend HTML.
- `RailCard`: arrivals default; station directory loads on focus and searching is
  local. Board requests occur only on explicit Check/Refresh. There is no polling,
  retry timer or fetch on language/direction changes. Frontend requests abort after
  ten seconds or when superseded/unmounted. A browser abort is not proof that a
  provider request was cancelled, so do not count it as provider quota protection.
- No Rail-specific server-side rate limiter, concurrency cap, cache or request
  coalescing is present. Check/Refresh remains clickable while a request is pending;
  scripted clients can bypass UI behaviour and call the public endpoint directly.
  The pacer for the separate OS Names service does not protect Rail.

**P2 public-launch capacity gap:** obtain the subscribed RDM quota/policies and
verify existing ingress protection before unrestricted public release. If it does
not already provide suitable protection, a simple rule on the existing ingress
(per-client limit plus an aggregate ceiling consistent with the actual quota) is
preferable to new infrastructure. If the existing ingress cannot provide that,
review a small bounded in-process guard separately. No numeric quota is inferred
from the agreements. UI-only disabling/debouncing is insufficient as the sole
control. No Redis, database, polling system or limiter was added in this task.

## Attribution, user expectations and preliminary licensing findings

The companion frontend replaces the standalone National Rail source line with
one linked bilingual disclaimer beside the results. It retains National Rail
attribution without endorsement language. The snapshot limitation, generated
provider timestamp, unknown-timing count and conditional Delay Repay explanation
remain. It does not claim that an empty station board means all trains are clear.
The Great Britain station directory retains source attribution, ODbL 1.0 licence
copies and the public notice; Northern Ireland is explicitly outside that directory.

Based solely on the owner-supplied preliminary review of the agreements:

- Arrival: public redistribution is permitted, with National Rail attribution;
  open/free licence; no additional commercial-use restriction was identified in
  Schedule 1 Special Conditions.
- Departure: public redistribution is permitted with National Rail attribution;
  open/free licence; no resale as-is or substantially unmodified. Commercial use
  requires material augmentation, integration or other added value. The UI adds
  disruption interpretation, bilingual presentation, station selection and journey
  context; whether this satisfies the agreement remains a release-review decision,
  not a legal conclusion from these code changes.
- Platform Consumer Agreement: protect credentials; excessive use materially
  impacting capacity can cause throttling or suspension. Data Sharing Agreements
  continue to apply.

**Unresolved licensing item (P2): RDM Platform Agreement Schedule 2 — Policies was
referenced but absent from the reviewed 16-page PDF.** Obtain and review that
attachment before final public-release compliance sign-off, or explicitly resolve
its status with the owner/provider. Do not invent policy terms or claim full legal
compliance. This does not block local development or deterministic testing. This
review is preliminary and is not a legal guarantee; the underlying agreements
were not independently re-reviewed in this task.

## Release checklist and findings

- [ ] Owner confirms both subscribed products, approved URLs and effective protected
      backend settings without disclosing keys.
- [ ] Existing production `/api/rail/` forwarding, JSON/status/no-store preservation,
      static directory/notice assets and same-origin behaviour verified.
- [ ] Actual provider quota and existing ingress controls reviewed; resolve the
      capacity gap before unrestricted public exposure.
- [ ] Missing Schedule 2 and commercial Departure use assessment resolved for
      public-release sign-off.
- [ ] Review local commits and approve push before updating the two draft PRs;
      then independent review and separate merge/deployment approvals.

No new P0 or P1 was established by this scoped inspection. P2 items are the missing
policy attachment, unverified production wiring and the unverified aggregate usage
protection. A confirmed production misconfiguration or provider restriction could
change their priority. Do not treat local test success as proof of production
configuration or full licence compliance.
