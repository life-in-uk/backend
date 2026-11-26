# Sponsored employment review

**GO for owner editorial review. DRAFT.** Both Guides are complete in zh-CN, zh-TW, en, ur and hi. Independent professional immigration and fluent human language review remain outstanding, particularly Urdu and Hindi. The review used current official sources and cross-locale checks. All people, offers and calculations in examples are fictional.

## Checks completed

- Checked the Home Office sponsor register and the difference between legal-entity licensing and sponsorship for the exact vacancy. Recruitment guidance supports applying early, checking individual deadlines and searching rolling roles in parallel. It describes practical recruitment patterns. [Sponsor register](https://www.gov.uk/government/publications/register-of-licensed-sponsors-workers) · [University of Liverpool careers FAQ](https://www.liverpool.ac.uk/careers/employability/international-plus/faqs/)
- Verified Appendix Skilled Worker SW 1.5A, SW 5 to 7, SW 12 and SW 14. The salary example separates the general threshold and occupation rate, keeps the part-time floor distinct, and excludes bonus pay. The 18-month plus three-year example exceeds the combined four-year limit. The price, hours and occupation assumptions are illustrative. Recheck applicable tables and law at release. [Appendix Skilled Worker](https://www.gov.uk/guidance/immigration-rules/immigration-rules-appendix-skilled-worker) · [Caseworker guidance](https://www.gov.uk/government/publications/skilled-worker-visa-caseworker-guidance/skilled-worker-caseworker-guidance-accessible)
- Verified sponsor-side CoS fee rules and official reporting. Personal visa fees are separate. If the most appropriate SOC, agency assignment or immigration history is unclear, flag the issue for qualified review. [Sponsor guidance](https://www.gov.uk/government/publications/sponsor-a-skilled-worker/workers-and-temporary-workers-sponsor-a-skilled-worker-accessible) · [Visa scam reporting](https://www.gov.uk/report-suspicious-emails-websites-phishing/report-visa-and-immigration-scams)
- Reviewed the live catalogue and source handoffs. All ten prospective API details returned 404, 20 existing locale-specific API link checks passed, and all 22 unique Guide source URLs returned HTTP 200. The [research table](RESEARCH.md) preserves public evidence and inaccessible sources.

On 26 November 2026 the final check confirmed that the general threshold is not pro-rated for part-time work, that the occupation rate is adjusted to the contracted hours, and that eligible new entrants must meet the applicable code, table and route requirements. The revised safety check distinguishes hiring a person out to fill a client position, including a temporary assignment, from a genuine service or project controlled by the sponsor. It also separates prohibited charges for finding work from permitted optional services and applicant visa fees. The expiry section uses existing permission and a valid in-time application as separate checks. Wider earned-settlement reform remains distinct from changes with a confirmed commencement date. [Agency fee guidance](https://www.gov.uk/agency-workers-your-rights/fees) · [Statements of changes](https://www.gov.uk/government/collections/immigration-rules-statement-of-changes)

## Technical checks

| Validation | Result |
|---|---|
| Locale, arithmetic, links, file hashes and canonical hashes | Both checks passed in `validate-drafts.py`. |
| Java Guide reader and quality gate | All ten passed, with zero warnings and matching canonical hashes. |
| Backend | `mvn -B -ntp -o verify` passed. 1,242 passed, 53 skipped, zero failures. |
| Publisher regression and rehearsal | All 50 tests passed. Local pack/check and the generic runner accepted the exact Work Visa scope and selected `family-visa-v1` with mocked host operations. |
| Frontend fixture preview | All ten localized routes passed at 320 px, with canonical URLs, five hreflang destinations, Urdu RTL, and one section-aware disclaimer per Guide using the PR #48 preview. |

Local ZIP SHA-256 `6eb96f962b34ad60477624ff8a2ae283e35871d14c15321f8678cbd49584a571`. Manifest SHA-256 `11bb1c7bd13fe87342661616997f30057f021c3784cfc880d614b5f20e9bc311`.

After the owner reviews the texts, recheck dates and rules, verify the exact merged digests and obtain separate approval for staging and protected publication. Confirm the curated Work Visa hub can expose both slugs before their frontend release. Leave the Phase 1 articles and the shared frontend disclaimer unchanged. [Protected runbook](../../../../deploy/PUBLISHING.md)
