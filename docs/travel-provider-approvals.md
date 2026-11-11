# Travel provider approvals: current scope and proposed update

Prepared 2026-11-11 for owner review. **Nothing here is active.** The recorded qualification rows
in `source_endpoint` were not modified. The proposed text below is an owner decision record for
review. It is **not provider permission**, and it does not replace the providers' licences.

## 1. What is approved today (recorded qualification, verbatim excerpts)

### TfL Underground (`tfl-underground-status`), recorded 2026-10-03T15:47:08Z
- Record: "Owner approves official Underground operational line-status acquisition, exact immutable
  raw evidence while retained, deterministic interpretation and future Travel Live
  current-state/change processing."
- Policy: "High-frequency raw TfL evidence has a default target retention of approximately 72 hours
  from EvidenceArtifact observation time … TfL/provider licence or terms override this default."
- **Scope:** acquisition and Travel Live current state are approved. No polling cadence is
  recorded. 72 hours is the retention target, and V11 implements exactly that.

### National Highways (`national-highways-road-closures`), recorded 2026-10-04T12:37:37Z
- Record: "Issue #26: one bounded live unplanned acquisition authorised."
- Policy: "This decision establishes no new retention duration, republication wording or polling
  cadence … Parsing, Current State and cleanup remain outside this approval."
- **Scope: one bounded acquisition only.** It does **not** authorise continuous production
  polling, a retention period, or republishing closures in the frontend. The local live test on
  2026-11-11 (6 runs) was a separate, explicit owner approval for that session only.

## 2. Provider terms (as read)

### TfL Transport Data Service licence
Read 2026-11-11 from https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service
(rendered page). The relevant "Requirements" text, verbatim:

> You must, where You do any of the above: Acknowledge TfL as the source of the Information by
> including the following attribution statement 'Powered by TfL Open Data'. Acknowledge that this
> Information contains Ordnance Survey derived data by including the following attribution
> statement: 'Contains OS data © Crown copyright and database rights 2016' and Geomni UK Map data ©
> and database rights [2019]. … Limit traffic requests up to a maximum of 500 calls per minute per
> data feed. TfL reserves the right to throttle or limit access to feeds …

- **OS/Geomni:** the statement is worded as a general licence requirement ("this Information
  contains Ordnance Survey derived data"). It is **not limited** to datasets that visibly contain
  map data.
  - The stored `/Line/Mode/tube/Status` payload contains no coordinates.
  - `routeSections`, `affectedStops` and `affectedRoutes` are empty in the current responses.
  - Even so, nothing in the licence exempts it.
  - **Recommendation:** show the OS/Geomni statement next to "Powered by TfL Open Data" in the
    Underground tool, unless TfL confirms in writing that it is not required for line status.
  - **Remaining uncertainty:** the bracketed years ("2016", "[2019]") look like a template.
    Whether to show them verbatim or updated is a question for TfL.
- **Rate:** 500 calls per minute per feed. The default PT2M refresh is 0.5 calls per minute.
- **Retention:** no retention or caching clause was found on this page. The 72-hour period is
  Life in UK's own target.

### National Highways developer terms
- **Attribution:** "Powered by National Highways' Transport Data Feeds", paragraph 20(a), read
  2026-11-10. It is already shown in the frontend.
- **Rate:** 10 requests per minute per key. The existing `RequestPacer` allows at most 9 in any
  rolling minute, and a run is capped at 8 pages.
- **Retention and republication:** not established in this repository. Review the terms before
  approving §3.2.

## 3. Proposed updated approval records (DRAFT, for owner review; not activated)

Activation would be a deliberate source-owned operation, with an owner-reviewed update of
`qualification_record` and `use_retention_policy`. There is no API for it, and it hasn't been
done.

### 3.1 TfL Underground (proposed)
"Owner approves continuous Travel Live acquisition of `https://api.tfl.gov.uk/Line/Mode/tube/Status`
at a fixed delay of no less than 2 minutes (≤ 30 calls/hour, far below the licence's 500 calls per
minute per feed), with back-off on failure or HTTP 429. Raw evidence is immutable while retained
and expires 72 hours after observation through migration V11. The current state is kept
independently. Public display carries 'Powered by TfL Open Data' and the OS/Geomni
acknowledgement required by the Transport Data Service licence. TfL terms override this decision."

### 3.2 National Highways road closures (proposed; requires explicit licensing approval)
"Owner approves continuous Travel Live acquisition of unplanned road and lane closures at a fixed
delay of no less than 10 minutes. Each run is a six-hour window of at most 8 provider-issued pages,
requested sequentially at least 7 seconds apart, with no retry and back-off on failure or HTTP 429.
Raw evidence is immutable while retained and expires 7 days after completion through migration
V11. Public display shows closures near a searched place with 'Powered by National Highways'
Transport Data Feeds' (paragraph 20(a)), without National Highways logos. The provider's status is
shown unchanged. Where status is active but the estimated end has passed, the UI explains that the
source has not yet confirmed closure. This approval is conditional on confirming that the developer
terms permit this retention and public republication."

## 4. Rail

`life-in-uk.rail-access.enabled` defaults to `false`. While it is off, no Rail endpoint contacts
RDM, even if Rail credentials are present; responses are `503 RAIL_DISABLED`. It stays off until
the RDM Schedule 2 policy review, licensing and rate-limit requirements are complete.
