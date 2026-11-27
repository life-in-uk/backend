# UK consumer credit editorial handoff

**DRAFT — GO for owner editorial review.** All four Money Guides are complete in zh-CN, zh-TW, en, ur and hi. See the [overlap audit](AUDIT.md), [reader evidence](RESEARCH.md), [source register](SOURCE-REGISTER.md) and [regulatory boundaries](REGULATORY.md). This package covers UK consumer credit generally. Individual lender criteria, contracts and local debt solutions still vary.

## Review findings

- Section 75 uses the eligible cash price, greater than £100 and no more than £30,000, alongside the requirement for a qualifying linked transaction and a breach of contract or misrepresentation. The £50 deposit on a £600 purchase illustrates the price test. Chargeback follows card-scheme rules. The fraud procedure uses the issuer's official contact routes and separates unknown payments from supplier disputes.
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

Local draft ZIP SHA-256 `640becfe4b4ca947a5a788de1777b3be6b7bd5c28d3998fa22710a3940ffa4d9`. Release manifest SHA-256 `47ca267e1694d17f71a1fedfc9029eee2f53ed2eed4552b39b4ab5776187dbda`. Per-variant canonical hashes are in the four editorial manifests.
