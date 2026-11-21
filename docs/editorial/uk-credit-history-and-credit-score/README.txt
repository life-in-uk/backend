UK credit history and credit scores for newcomers
Editorial handoff · 21 November 2026 · DRAFT awaiting owner approval

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
- A protected Production inventory check on 21 November used a read-only
  transaction and returned metadata only. It found no exact slug in 68
  Guide records, 75 source revisions, 74 content batches or 370 locale
  revisions. The only related title or metadata match was the existing
  renting-guarantors Guide in Renting. The two existing Money slugs were
  the bank account and National Insurance number Guides. Repeat the
  duplicate check if import is deferred and other editorial work intervenes.

Source and language review

- The final editorial pass checked all 20 sources against the publishers'
  current material on 21 November 2026. Each variant uses the same eight
  evidence keys and canonical source metadata.
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
- The final language pass checked all five variants for meaning, terminology
  and consistency. It clarified that the statutory report and consumer score
  are separate, tightened the electoral and judgment timing wording, and
  smoothed Urdu and Hindi phrasing. This was a separate pass by the drafting
  assistant. No external human or native-speaker sign-off is recorded.
  A local fixture confirmed Urdu RTL and the linked English agency names.
- Report any factual or translation correction before locking the source
  revision. Recompute the hashes after any edit.

Technical checks and publication boundary

- The existing Java GuideImportReader parsed all five DRAFT files. It checked
  evidence references and computed the canonical digests. The standard-v1
  quality check passed all five with no warnings or blocking findings. The
  date warning now recognizes dated labels in all five locales.
- GuideImportReaderTest, GuideEvidenceValidationTest, GuideContentDigestTest
  and GuideQualityGateTest passed locally (148 tests).
- The existing Money & Finance hub listed all five published test fixtures
  under Money; all article routes had the expected canonical URL and hreflang.
  The draft was absent from the mocked published list. No frontend code change
  is required. Run the normal frontend release build after approved publication
  to generate the five public detail pages.
- The files live under docs/editorial. The manifest is a review inventory.
  The package is outside the Education and Household release allowlists.
- After owner approval of the reviewed package, use the existing owner
  editorial process for a private Money batch. Lock the reviewed zh-CN source,
  verify its revision and hash, attach all four reviewed locale variants to
  that exact source revision, and check their server revision IDs and hashes.
  Use the existing standard-v1 checklist, owner authorization and protected
  publication procedure. Keep all five locales private until authorized.
