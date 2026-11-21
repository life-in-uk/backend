# Foundation batch review

Both Money Guides are **DRAFT** in zh-CN, zh-TW, en, ur and hi. The companion programme lists later work. It does not green-light any later tax figures or calculator implementation.

| Guide | Applicable period | Worked example |
|---|---|---|
| `uk-paye-tax-codes-national-insurance` | UK employment, 2026/27 | £3,000 regular monthly gross. Shows £4,686 annual Income Tax in England, Wales and Northern Ireland, £4,711.07 in Scotland, and £156.16 monthly employee NI in the example. Separately illustrates a £12 saving on a qualifying £60 expense wholly in a 20% band. |
| `uk-self-assessment-register-file-pay` | UK personal returns for 2025/26; explicitly dates 2026/27 advance payments | A £2,400 prior-year balance and first £1,200 payment on account produce a £3,600 January bill. The second £1,200 is normally due in July. Shows registration, UTR, filing and payment as separate steps, and amendment, penalty and adviser routes. |

## Source verification · 21 November 2026

The 26 distinct canonical URLs in the Guide source registers returned HTTP 200. Read the current HMRC pages for the scope and operative rules. Cross-checked Scottish bands and Welsh budget parity with devolved publications. The reader-facing examples keep the geography and date explicit. No 2027/28 rates or thresholds are presented as current rules.

- PAYE and employee Class 1 example basis. [HMRC annual rates and allowances](https://www.gov.uk/government/publications/rates-and-allowances-income-tax/income-tax-rates-and-allowances-current-and-past) · [HMRC 2026/27 payroll figures](https://www.gov.uk/guidance/rates-and-thresholds-for-employers-2026-to-2027) · [Scottish Budget tax policy](https://www.gov.scot/publications/scottish-budget-2026-2027/pages/4/) · [Welsh Government final budget](https://www.gov.wales/sites/default/files/publications/2026-01/final-budget-2026-2027-report.pdf) · [HMRC employee expenses](https://www.gov.uk/tax-relief-for-employees/overview)
- Filing eligibility, registration, deadline and payment method. [Who needs to file](https://www.gov.uk/check-if-you-need-tax-return) · [Registration](https://www.gov.uk/register-for-self-assessment) · [2025/26 deadlines](https://www.gov.uk/self-assessment-tax-returns/deadlines) · [Online filing](https://www.gov.uk/log-in-file-self-assessment-tax-return) · [Card payment reference](https://www.gov.uk/pay-self-assessment-tax-bill/by-debit-or-credit-card-online)
- Payments on account, amendments, penalties and filing exceptions. [HMRC payments on account](https://www.gov.uk/understand-self-assessment-bill/payments-on-account) · [Taxes Management Act 1970, section 59A](https://www.legislation.gov.uk/ukpga/1970/9/section/59A) · [Section 9ZA](https://www.legislation.gov.uk/ukpga/1970/9/section/9ZA) · [HMRC corrections](https://www.gov.uk/self-assessment-tax-returns/corrections) · [MTD](https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax/using-making-tax-digital-for-income-tax-for-the-first-time)

## Validation

- `python3 -B docs/editorial/personal-tax/validate-foundations.py` passed all three checks. These cover independent arithmetic, date and amount parity in the five locales, DRAFT identity, file and canonical hashes, source and locator parity, and localized links.
- The production Java Guide reader and quality gate read all 10 drafts. The Java canonical digests matched all 10 handoff hashes. Zero warnings or blocking findings.
- Existing backend Guide tests finished with 243 passed, two skipped. The 50 publisher unit tests passed. Offline Money manifest pack/check accepted both slugs and all ten variants. The ZIP remains local.
- Rendered the 10 drafts with the existing frontend static renderer at 320 px. Canonicals, hreflang, locale links, evidence anchors and Urdu RTL rendered as expected. This preview used draft fixtures and did not create public pages.

**GO for owner review. Publication pending.** Recheck time-sensitive rates, HMRC filing and MTD instructions at release. Review the multilingual wording, especially Urdu and Hindi. No independent human or native-speaker sign-off has taken place. Publish both foundation Guides together because their locale links point to each other. The calculator specification is a separate approval decision and has no runtime implementation.
