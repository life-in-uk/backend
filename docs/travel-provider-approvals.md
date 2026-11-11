# Travel provider approvals: recorded scope

Updated 2026-11-11. This page records Life in UK's **internal** owner approvals for the Travel
providers and how they relate to the providers' licences.

- An internal approval is an owner decision. It is **not provider permission**, and the providers'
  licences always take precedence.
- **Production polling is disabled.** Both refresh schedules are off by default
  (`life-in-uk.travel-refresh.*.enabled=false`), and no production environment has enabled them.

## 1. National Highways (`national-highways-road-closures`)

### 1.1 Original onboarding and qualification (2026-10-04)
- **Subscription:** the owner registered on the National Highways Developer Portal, accepted its
  Licence Agreement (https://developer.data.nationalhighways.co.uk/terms), and holds an active
  Production subscription for Road and Lane Closures v2.0.
- **Qualification:** recorded 2026-10-04T12:37:37.502940339Z through
  `SourceEndpoint.qualify(record, policy)`:
  - Record: "Explicit owner approval … based on previously owner-reviewed official developer terms
    … and active Production subscription. Issue #26: one bounded live unplanned acquisition
    authorised."
  - Policy: "… This decision establishes no new retention duration, republication wording or
    polling cadence …"
- **History:** life-in-uk/backend Issues #24 and #26, and PRs #25 and #27, all 2026-10-04. Issue
  #26's owner decision already set out central (not per-user) acquisition, `closureType=unplanned`
  with a six-hour window, and an intended cadence of about 10 minutes. The first live run succeeded
  that day.

### 1.2 Internal scope amendment (2026-11-11)
The owner authorised the following scope, appended to the endpoint's `qualification_record` and
`use_retention_policy` (version 1 → 2). The 2026-10-04 text is preserved verbatim ahead of the
amendment.

| Item | Approved internal scope |
| --- | --- |
| Data | Road and Lane Closures v2.0, `GET /roads/v2.0/closures`, `closureType=unplanned` only |
| Query window | Six hours ending at one captured UTC acquisition instant |
| Refresh | About every 10 minutes, fixed delay, **never more often than every 5 minutes** |
| Requests | At most **8** provider-issued paginated requests per refresh, strictly sequential, **at least 7 seconds apart** |
| Rate limit | Never above the published **10 requests per minute per subscription key** (at most 9 in any rolling minute); no retry within a refresh; back-off after repeated failures and immediately on HTTP 429 |
| Raw evidence | Kept exactly as received and immutable while retained, for **up to 7 days** after run completion, then expired only through the reviewed controlled-expiry path (`V11__travel_evidence_expiry`, PR #53, not yet applied) |
| Current state | Independent lifecycle; never shown as current once stale (frontend 30-minute freshness rule) |
| Public display | Closures relevant to a user-searched place or device location only, never the full national dataset by default; provider status shown unchanged; the attribution "Powered by National Highways' Transport Data Feeds" (paragraph 20(a)); no National Highways logos (paragraph 20(b)) |
| Credential | External configuration, request header only, never in URLs, logs or stored provenance |

- **Where it is recorded:** the amendment is in the **local development database only**. A
  production database must receive the same amendment deliberately, as a reviewed operation, before
  production polling.
- **What it does not do:** it does not itself enable polling.
- **Audit evidence:** `~/projects/life-in-uk-backend-backups/2026-11-11-national-highways-terms/`
  contains the before and after rows, the exact amendment text, and `AUDIT.md`, all covered by
  SHA-256 checksums in `SHA256SUMS`.

### 1.3 Licence terms relied on (archived 2026-11-11T15:23:45Z)
The licence and FAQ were archived as rendered text, HTML and PDF. Their SHA-256 values: licence
text `b887a48ff51d5a7131a04109c95fbdaf1dde0e084dfdc6655bc4655d89a0fcf2`, FAQ text
`3079c742b4f4b93c39ef4091b17244a9c641e70d066a96877d154ea8170e7964`.

- **Paragraph 7:** "a worldwide, royalty-free, perpetual, non-exclusive Licence to use the
  Information", subject to its conditions.
- **Paragraph 19:** free to "copy, publish, distribute and transmit", "adapt" and "exploit … by
  including it in Your own product or application".
- **Paragraph 20(a):** attribution "Powered by National Highways' Transport Data Feeds". **20(b):**
  follow the branding guidelines; no logos are used.
- **Paragraph 21(c):** no rights to use the Information after the licence has ended.
- **Paragraph 2:** National Highways may revise the licence without notice. Re-check it before
  production activation and periodically.
- **FAQ:** "The APIs have a rate limit of 10 calls per subscription key per minute".
- **Retention:** the licence sets no retention period.
- **Gap:** the exact version accepted on 2026-10-04 was not archived at the time, and the portal
  does not date or version its terms.

### 1.4 Paragraph 21(e): interpretation and remaining ambiguity
> This Licence does not … (e) permit You to use any automated system, software or process to
> extract content and/or data, including trawling, data mining and screen scraping, except where
> expressly permitted under a separate written licence agreement with NH.

- **Owner interpretation:** the clause addresses scraping-style extraction. It does not cover use
  of the key-authenticated Developer Portal API that the subscription provides, within the rate
  limit National Highways publishes for that API.
- **Remaining ambiguity:** read literally, a scheduled poller is "an automated … process to extract
  … data". **National Highways has not confirmed this interpretation in writing.** It is an
  internal owner decision.

## 2. TfL Underground (`tfl-underground-status`)

### 2.1 Recorded qualification (2026-10-03T15:47:08Z, unchanged)
- Record: "Owner approves official Underground operational line-status acquisition, exact immutable
  raw evidence while retained, deterministic interpretation and future Travel Live
  current-state/change processing."
- Policy: "High-frequency raw TfL evidence has a default target retention of approximately 72 hours
  from EvidenceArtifact observation time … TfL/provider licence or terms override this default."
- **Scope:** acquisition and Travel Live current state are approved, and 72 hours is the retention
  target that V11 implements. No polling cadence is recorded.

### 2.2 TfL Transport Data Service licence (read 2026-11-11)
From https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service (rendered page),
"Requirements", verbatim:

> You must, where You do any of the above: Acknowledge TfL as the source of the Information by
> including the following attribution statement 'Powered by TfL Open Data'. Acknowledge that this
> Information contains Ordnance Survey derived data by including the following attribution
> statement: 'Contains OS data © Crown copyright and database rights 2016' and Geomni UK Map data ©
> and database rights [2019]. … Limit traffic requests up to a maximum of 500 calls per minute per
> data feed. TfL reserves the right to throttle or limit access to feeds …

- **Attribution:** both statements are shown in the Underground tool (frontend PR #23), reproduced
  verbatim including the licence's years and brackets. Whether "[2019]" is meant literally is
  unconfirmed.
- **Rate:** the default 2-minute refresh is 0.5 calls per minute.
- **Retention:** the licence has no retention clause. 72 hours is Life in UK's own target.

### 2.3 Proposed cadence record (not recorded)
"Owner approves continuous Travel Live acquisition of `https://api.tfl.gov.uk/Line/Mode/tube/Status`
at a fixed delay of no less than 2 minutes (≤ 30 calls/hour), with back-off on failure or HTTP 429.
Raw evidence expires 72 hours after observation through migration V11." The TfL record has not been
amended. Recording this cadence is an owner decision.

## 3. Rail

`life-in-uk.rail-access.enabled` defaults to `false`. While it is off, no Rail endpoint contacts
RDM, even if Rail credentials are present; responses are `503 RAIL_DISABLED`. It stays off until
the RDM Schedule 2 policy review, licensing and rate-limit requirements are complete.
