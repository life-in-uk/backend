# Editorial review and release readiness

**GO for owner review · DRAFT.** Two Work Visa Guides, each complete in zh-CN, zh-TW, en, ur and hi. Owner approval, an independent immigration wording review, and independent fluent-language review remain pending. Urdu and Hindi received a consistency and readability pass in this assignment. Native fluency and human sign-off remain unverified.

## Immigration checks and open review items

- Appendix Graduate GR 8.1 distinguishes the application cut-off from the decision date that starts the grant. A qualifying non-doctoral application before 1 January 2027 is on the two-year side of the transition. Qualifying applications from that date get 18 months. The doctorate rule remains three years. Recheck the amounts before release. [Appendix Graduate](https://www.gov.uk/guidance/immigration-rules/immigration-rules-appendix-graduate) · [HC 1333](https://www.gov.uk/government/publications/statement-of-changes-to-the-immigration-rules-hc-1333-14-october-2025) · [Fees](https://www.gov.uk/graduate-visa/how-much-it-costs)
- Treat the sponsor's successful completion notification as the normal application prerequisite. Where UKVI cannot see a report but the file would otherwise qualify, the caseworker guidance calls for checking with the sponsor. The reader should never plan an early application around that operational safeguard. Ask an adviser to review late results, resits, gaps in permission and unusual CAS history. [Graduate caseworker guidance](https://www.gov.uk/government/publications/graduate-caseworker-guidance/graduate-caseworker-guidance-accessible-version)
- Check Student ST 26.6 and the individual's conditions before starting a permanent role while awaiting a decision. Check section 3C separately for permission and travel. The pending-application travel rule describes the wider Common Travel Area. Departure from the UK can independently end section 3C permission. The UK-born child limb and separately timed family applications require care. [Appendix Student](https://www.gov.uk/guidance/immigration-rules/immigration-rules-appendix-student) · [Section 3C guidance](https://www.gov.uk/government/publications/3c-and-3d-leave/3c-and-3d-leave-accessible) · [Graduate dependants](https://www.gov.uk/graduate-visa/your-partner-and-children)
- Separate Skilled Worker new entrant eligibility and the four-year combined permission cap from the qualifications for settlement. Verify the occupation, eligible pay, hours, contract and permission record. Recheck the wider earned-settlement programme as changes commence. [Appendix Skilled Worker](https://www.gov.uk/guidance/immigration-rules/immigration-rules-appendix-skilled-worker) · [Earned settlement response](https://www.gov.uk/government/consultations/earned-settlement/outcome/a-fairer-pathway-to-settlement-government-response-to-the-consultation)
- Review language for the differences between permission, an application, a decision and an employer's intention. Retain standard English route names and official identifiers beside natural locale explanations. Review Urdu bidirectional wording around numbers and acronyms on a phone.

## Validation evidence

| Check | Result |
|---|---|
| Catalogue and duplicate review | 75 public Guides per locale, 425 sitemap URLs, 220 relevant localized detail bodies checked. Both proposed slugs were absent from the catalogue and all ten public detail APIs returned 404. Repository and available local editorial inventory checked. The protected runner must still reconcile private records before writes. |
| Sources and links | 37 canonical source URLs and 30 existing localized detail links returned HTTP 200. Draft peers were rendered locally. Community evidence supports question selection only. |
| Editorial parity | `python3 -B docs/editorial/international-student-pathways/validate-drafts.py` passed both checks across ten files. These check locale completeness, dates, worked prices, canonical and file hashes, evidence and links. |
| Production Java Guide reader and quality gate | All ten passed. Java hashes matched the editorial manifests with zero blocking findings and zero warnings. |
| Backend and publisher | `mvn -B -ntp -o verify` gave 1,242 passed, 53 skipped, zero failures. All 50 publisher regression tests passed. |
| Offline rehearsal | Existing pack and check accepted the exact two-slug `work-visa` archive. The generic runner loaded it, selected `family-visa-v1`, and passed the pinned scope to mocked host staging and publication. |
| Frontend local preview | The current renderer produced ten detail pages at 320 px. Checked headings, evidence anchors, canonical, alternates, localized links, horizontal overflow and Urdu RTL. These were draft fixtures. |

Offline archive SHA-256 `8ed6f9f53c8de1c0cd02a2e51ec0021c268728e1408e108c711efc1911e71222` and manifest SHA-256 `7b640b2725ce9dba2a17f22fabe46029a86ec14598a7ca94decdc53c4e818c8d`. The archive is local review evidence.

## Before a future release

1. Approve the ten exact editorial hashes after factual and language review. If content changes, regenerate both handoff manifests and rehearse the new archive.
2. Resolve discoverability in the curated Work Visa hub for all five locales before public rollout. The existing detail route and static renderer work. Frontend curation and any resulting release follow separate scope and approvals.
3. Use the existing manifest-driven [protected publisher](../../../deploy/PUBLISHING.md) for these two new slugs together, with release review and verified backups. Follow the separate frontend release process for public static pages.
