# UK consumer credit editorial handoff

**Final editorial and technical review, 27 November 2026 — GO for owner review.** All four Money Guides are complete in zh-CN, zh-TW, en, ur and hi. See the [overlap audit](AUDIT.md), [reader evidence](RESEARCH.md), [source register](SOURCE-REGISTER.md) and [regulatory boundaries](REGULATORY.md). This package covers UK consumer credit generally. Individual lender criteria, contracts and local debt solutions still vary.

## Review findings

- Section 75 uses the eligible cash price, greater than £100 and no more than £30,000, alongside the requirement for a qualifying linked transaction and a breach of contract or misrepresentation. The individual £600 cash price, rather than the £50 deposit, illustrates the price test. The final review clarified individual items, the potential Section 75A route for qualifying linked credit, and the variable chargeback window, typically around 120 days for goods and services. Civil protection and card-scheme recovery are distinct. [Section 75](https://www.legislation.gov.uk/ukpga/1974/39/section/75) · [Section 75A](https://www.legislation.gov.uk/ukpga/1974/39/section/75A) · [Ombudsman dispute guide](https://www.financial-ombudsman.org.uk/consumers/complaints-can-help/credit-borrowing-money/goods-services-bought-credit) The fraud procedure uses the issuer's official contact routes and separates unknown payments from supplier disputes.
- Card guidance presents 0% by transaction type, with terms, fees, deadlines, same-group restrictions where applicable and repayment allocation. Builder guidance avoids universal utilisation percentages and discourages carrying interest to build credit.
- Examples use fictional figures. The numerical test independently derives the fee and instalment amounts for the balance transfers, applies the cap and charges to the cashback example, and multiplies the two loan payment schedules. It checks all figures across all five locales. The 51% representative APR rule refers to resulting credit agreements, as assessed by the firm, rather than acceptance odds.
- Existing Money Guides and article links were checked in all locales. The drafts contain localised general-information and financial-advice disclosures and use free debt guidance. Provider terms serve as source evidence. No rankings, current advertised rates, promotional links, referral forms or application flows appear in the Guides.
- Independent professional financial or regulatory review and independent fluent human-language review are outstanding. Ask reviewers to focus on Urdu and Hindi wording, consumer-law distinctions, risk language and the examples. Recheck current FCA rules, provider terms and source accessibility before publication.

## Validation

| Check | Result |
|---|---|
| Editorial structure, five-locale completeness, evidence parity, internal links, worked numbers and manifests | Both validator tests passed. |
| Java Guide reader and GuideQualityGate | All 20 accepted with zero warnings. Java canonical hashes matched the package and per-Guide manifests. |
| Backend Maven verify | 1,242 passed, 53 skipped, zero failures. |
| Offline publisher | All 50 regression tests passed. Manifest pack/check and rehearsed generic runner accepted the exact four-slug Money scope and `standard-v1`. Mocked retry used its existing checkpoint. |
| Mobile browser preview | All 20 routes passed at 320 px, including Urdu RTL, canonical URLs, five locale alternates, no horizontal overflow, and one inline financial-information disclaimer per Guide. |
| Publication | Draft repository files and local-only review archive. Future releases use the existing protected manifest publisher after separate owner authorization. |

Local draft ZIP SHA-256 `6300b494e29988b973d0da53819ac0edab9ecb38582bfb055732f20cff41cc56`. Release manifest SHA-256 `508efef4ec820304ff59e10b8552c7f89e22835ef3b5be9aaf6d69271cc6b2a4`. Per-variant canonical hashes are in the four editorial manifests.

## Recalculated figures

| Example | Result and assumptions |
|---|---|
| Section 75 | £600 > £100 and £600 ≤ £30,000. The £50 card deposit is part-payment, with the other eligibility conditions still to be met. |
| Balance transfers | £2,400 × 3% = £72. £2,472 ÷ 12 = £206. £2,400 × 1.5% = £36. £2,436 ÷ 6 = £406. The difference is £36. Fees are added to the balance. No new use, late payments or interest in the example. |
| Cashback | 1% × £3,000 = £30 before the £25 cap. £25 − £24 = £1. An additional £200 at 3% costs £6 after the cap has been used. £1 − £6 = −£5. |
| Personal loans | £140 × 24 = £3,360 (£360 over principal). £100 × 36 = £3,600 (£600 over principal). The lower instalment saves £40 a month but increases the full cost by £240. Same £3,000 principal, no upfront fee, fixed instalments. |

All example results are exact in pounds, so there is no rounding difference between locales. The examples use hypothetical terms. Amounts actually due depend on the agreement, statement dates and timing of payment.

## SEO release coordination

Current baseline on 27 November 2026: the public backend sitemap has 455 unique canonical URLs. All ten current credit-history and bank-account detail URLs and their ten public API equivalents returned 200. All twenty proposed localized API routes returned 404 and were absent from the sitemap. The existing backend sitemap enumerates published records while the protected frontend build prerenders the public API inventory available at build time. API publication can therefore expose sitemap links before the static HTML deployment.

For Claude's SEO handoff and the owner: before authorizing backend publication, agree the paired static frontend release window and who will approve it, using any requirements from the SEO audit. After the separate protected backend run succeeds, verify the publication receipts, all twenty hash-matched API variants and sitemap listings. Use the existing protected frontend release to build against that live API snapshot immediately afterwards. Once the owner approves deployment, confirm the twenty detail URLs return 200 and that Money listings, canonical tags, hreflang, sitemap and mobile layout agree. Record any temporary sitemap-to-static gap and treat public frontend acceptance as pending until smoke checks pass. No frontend code change or mechanism is proposed here. [Frontend hosting runbook](https://github.com/life-in-uk/frontend/blob/2154a21d1ed5c2dae05e50830969064e31818463/docs/seo-hosting.md)
