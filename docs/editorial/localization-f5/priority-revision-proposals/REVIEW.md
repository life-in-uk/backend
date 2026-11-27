# F5 priority replacement proposals

**Draft · reviewed on 27 November 2026**

Both Guides keep their existing slugs and five locale identities. These are offline replacement proposals based on the public revision and hash snapshot in each manifest. They are not accepted by the new-slug publisher. See the [approved-process prerequisites](../RELEASE.md) before any production write.

| Guide | Reader-facing changes |
|---|---|
| NHS interpreter in England | English, Urdu and Hindi titles and summaries now match the reader's general question. The Guide separates an interpreter from written translation, asks readers to tell the GP their preferred language, and gives localized booking and 111 scripts. Urdu and Hindi examples express a language preference without promising that an interpreter is immediately available. The emergency wording points to 999 without delaying for language support. Chinese versions retain Mandarin and Cantonese examples and add a practical booking-format reminder. |
| Opening a UK bank account | English, Urdu and Hindi no longer presume a Chinese passport, phone number or address. They separate the provider's document requirements from nationality and give alternative document questions that apply to different newcomers. The NatWest example remains dated and qualified. Chinese versions retain scoped Chinese-document guidance and explicitly tell readers to check document type, language and translation requirements. |

## Evidence and limitations

- Rechecked NHS 111 interpreting, NHS England primary-care interpreting guidance and the 999 signpost. NHS England's primary-care PDF is a resource about primary care. Retained hospital arrangements and clinical content need their normal source and clinical review before release. [111](https://www.nhs.uk/nhs-services/urgent-and-emergency-care-services/when-to-use-111/) · [NHS England primary care guidance](https://www.england.nhs.uk/wp-content/uploads/2018/09/guidance-for-commissioners-interpreting-and-translation-services-in-primary-care.pdf) · [999](https://www.nhs.uk/nhs-services/urgent-and-emergency-care-services/when-to-call-999/)
- Confirmed the stated NatWest newcomer example and FSCS deposit protection. The onboarding snapshot retains its October date; provider terms elsewhere in the retained text need a release-day recheck. The reconciled bank files in this directory now include the safeguarding section and evidence from the earlier proposal. Treat the older proposal as historical. See the [final F5 review](../final-health-revision-proposals/REVIEW.md). [NatWest documents](https://www.natwest.com/current-accounts/what-do-you-need-to-open-a-current-account.html) · [FSCS](https://www.fscs.org.uk/what-we-cover/banks-building-societies-credit-unions/) · [Earlier banking proposal](../../catalogue-expansion-2026-11/revision-proposals/open-uk-bank-account-new-arrival/)
- Review preserves shared legal and eligibility content. Existing reviewed excerpts and their reference keys are kept. Sources, locators, excerpts and their ordering remain aligned across locales. Independent clinical or financial review and independent fluent human review remain outstanding, especially for Urdu and Hindi.

## Checks

- All ten DRAFT variants round-trip through the Java reader and GuideQualityGate with zero warnings. Checks covered canonical and file digests, previous public source IDs and hashes, source/evidence parity and preserved canonical paths.
- Ten static previews passed at 320 px, with canonical URLs, reciprocal locale alternates, title, evidence anchors, link checks and Urdu RTL.
- Full backend verify recorded 1,242 passed, 53 skipped and zero failures. The existing publisher suite passed all 50 tests. No replacement package was staged or imported.

The public release hold remains in effect. Claude's About/policy copy is separate. The country-specific records decision and lower-priority Health work are tracked in the [F5 plan](../PLAN.md).
