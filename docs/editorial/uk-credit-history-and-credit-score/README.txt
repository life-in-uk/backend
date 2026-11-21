UK credit history and credit scores for newcomers
Editorial handoff · 21 November 2026 · DRAFT for review

Guide slug and review scope

- Slug: uk-credit-history-and-credit-score
- Existing category: money
- Source: drafts/zh-CN.json
- Translations: drafts/zh-TW.json, drafts/en.json, drafts/ur.json, drafts/hi.json
- Proposed public route after approval: /{locale}/money/uk-credit-history-and-credit-score
- Each file contains the localized title, summary, body, canonical source
  metadata and evidence. Use the title and summary with the existing Guide
  SEO conventions to avoid competing descriptions.
- EDITORIAL-MANIFEST.json records file SHA-256 and Java guide-content-v1
  SHA-256 for the local draft artifacts. The editor must compare staged
  revision IDs and server hashes independently before approval.

Duplicate check

- Production public Guide lists on 21 November contained 68 entries per
  locale, with no dedicated credit history, score, report or electoral
  register guide. The two Money Guides were
  open-uk-bank-account-new-arrival and national-insurance-number-newcomers.
- Checked the live bodies of both Money Guides and renting-referencing-checks,
  renting-guarantors, renting-letting-agent-overview and renting-energy in all
  five locales (30 variants). The related credit mentions concern banking,
  renting or utility applications. None explains how to get and correct a
  credit report or build a UK credit record.
- The frontend editorial and SEO catalogue tracks this as M2 in
  docs/seo/content-gap-backlog.md, a new Money Guide candidate. Searches of
  backend and frontend files, accessible saved Guide drafts and exports,
  branches and pull requests found no existing dedicated Guide.
- The owner-only Production editorial inventory was not accessible through
  the public endpoint. Check the protected editorial inventory for this
  slug and any equivalent private draft before creating a Production batch.

Source and language review

- 20 sources were checked against the publisher's current material. Each
  variant uses the same eight evidence keys and canonical source metadata.
- ICO identifies the three main consumer agencies. MoneyHelper also lists
  Crediva. All four statutory report routes are linked in the body. Some
  publishers reject direct command-line requests. Their published pages were
  checked through web research.
- Recheck Electoral Commission citizenship and franchise rules before
  publication, particularly for EU residents in England or Northern Ireland.
  The full and open registers are distinct. The guide gives no individual
  eligibility determination or estimated score gain.
- Recheck the FCA CONC 5.2A section before approval. The guide uses a
  proportionate affordability assessment for applicable regulated consumer
  credit. It makes no product recommendation, borrowing recommendation for
  building credit, or promise of acceptance, rates or score improvements.
- For a future factual revision, distinguish late-payment visibility from the
  default date and court judgment date. Keep the England and Wales CCJ example
  separate from Scottish decrees and Northern Ireland procedures.
- English and the four translations have been checked for section, source,
  factual and terminology parity at draft stage. Independent editorial review
  and fluent local-language review are pending, especially Urdu and Hindi.
  Review mixed-direction text, acronyms and links in Urdu in the existing
  preview. Keep stable source keys and English source locators across locales.
- Report any factual or translation correction before locking the source
  revision. Recompute the hashes after any edit.

Technical checks and publication boundary

- The existing Java GuideImportReader parsed all five DRAFT files. It checked
  evidence references and computed the canonical digests. An in-memory
  standard-v1 quality check had zero blocking findings. The four translations
  carry localized dated source lines. The source quality check recognizes the
  Simplified Chinese last-updated marker and flags its absence if run on
  translations.
- GuideImportReaderTest, GuideEvidenceValidationTest, GuideContentDigestTest
  and GuideQualityGateTest passed locally (136 tests).
- The files live under docs/editorial. The manifest is a review inventory.
  The package is outside the Education and Household release allowlists.
- After independent source and language review, use the existing owner
  editorial process for a private Money batch. Lock the reviewed zh-CN source,
  verify its revision and hash, attach all four reviewed locale variants to
  that exact source revision, and check their server revision IDs and hashes.
  Use the existing standard-v1 checklist, owner authorization and protected
  publication procedure. Keep all five locales private until authorized.
