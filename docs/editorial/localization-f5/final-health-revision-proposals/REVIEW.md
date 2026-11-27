# F5 final localization implementation

**Replacement drafts · 27 November 2026**

The first five F5 revisions were prepared in backend PRs #92 and #93. This PR finishes the remaining Health changes and reconciles the existing banking drafts. It does not include urgent dental, which remains on its existing publication. The Health hub implementation is frontend PR #53. [F12 editorial copy for Claude](https://github.com/life-in-uk/frontend/pull/55)

| Existing Guide | Draft change |
|---|---|
| GP registration | English, Urdu and Hindi now prioritize access to registration and relevant NHS resources. Both Chinese locales retain the Chinese leaflet. The edition and evidence section numbers now point to NHS England manual version 7, including §§4.10.4–4.10.9 on documentation and §4.11.3 on written refusal. |
| Records issued in China | Each title and introduction explicitly identifies documents issued in China and GP care in England. Document origin is distinct from nationality and spoken language. The body keeps the original guidance and adds locale-aware links to the general registration and interpreter Guides. |
| Where to go when ill | Titles specify England in all locales. English, Urdu and Hindi use the general NHS patient resource instead of a standalone Chinese-language source item. Chinese copy keeps its translated reference. Care routing is carried forward. |
| Finding a dentist | Titles explicitly cover England. Urdu wording identifies mydentist as a dental clinic network. The October 2026 example prices remain historical examples, as labelled in the article. Urgent dental care is unaffected. |
| First UK bank account | The priority localization remains the base. Carries forward the rechecked FCA safeguarding section from the previous #82 proposal, including its source and evidence references. Use this reconciled draft for the existing slug after separate approvals. The historical proposal remains in Git for traceability. |

## GP canonical evidence

Recovered the protected owner translation export and the accepted four-translation package for batch `9db0b663-a5f1-4b59-81f7-8506d9f0f659`. The recomputed export hashes for all five locales match the current live API on source revision `9ca7a5bc-90f3-424c-a79d-d99eb4949a90`. Titles, summaries, bodies, source timestamps (normalized) and evidence also match. The digest calculation uses the original stored draft metadata. In particular, the public API overlays publication timestamps, which accounts for the prior public-projection mismatch. The export stays private, and the draft manifest pins its verified parent hashes.

Checked references for changed content on 27 November 2026. Retained policy and dated provider examples should receive their normal release-day review. [NHS registration](https://www.nhs.uk/nhs-services/gps/how-to-register-with-a-gp-surgery/) · [NHS England PGM v7](https://www.england.nhs.uk/wp-content/uploads/2017/11/prn02549-primary-medical-services-policy-and-guidance-manual-full-v7.0-2026.pdf) · [NHS England patient information](https://www.england.nhs.uk/publication/you-and-your-general-practice/) · [UKHSA immunisation guidance](https://www.gov.uk/government/publications/vaccination-of-individuals-with-uncertain-or-incomplete-immunisation) · [FCA payment provider comparison](https://www.fca.org.uk/consumers/using-payment-service-providers) · [FCA safeguarding requirements](https://www.fca.org.uk/firms/emi-payment-institutions-safeguarding-requirements)

## Validation and release

All 25 files passed the Java Guide reader and quality gate with zero warnings. File and canonical hashes, published-parent hashes and five-locale source/evidence alignment passed. Static previews passed for all 25 at 320 px, including Urdu RTL and reciprocal hreflang. Full backend verify passed with 1,242 tests, 53 skipped and zero failures. The 50 publisher tests passed.

All existing URLs remain the release targets. Use the [existing append-only replacement process](../RELEASE.md), with refreshed publication and locale IDs, read-back, explicit approval and retained prior revisions for rollback. The current state is DRAFT. Independent clinical or financial review and independent fluent human-language review remain outstanding. The four Money Guides and these replacements stay on hold until the owner approves a release after Claude's publication-consistency work.
