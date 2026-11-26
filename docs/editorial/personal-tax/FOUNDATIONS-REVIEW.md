# Foundation batch review

Both Money Guides are **DRAFT** in zh-CN, zh-TW, en, ur and hi. The companion programme lists later work. It does not green-light any later tax figures or calculator implementation.

| Guide | Applicable period | Worked example |
|---|---|---|
| `uk-paye-tax-codes-national-insurance` | UK employment, 2026/27 | £3,000 regular monthly gross. Shows £4,686 annual Income Tax in England, Wales and Northern Ireland, £4,711.07 in Scotland, and £156.16 monthly employee NI in the example. Separately illustrates a £12 saving on a qualifying £60 expense wholly in a 20% band. |
| `uk-self-assessment-register-file-pay` | UK personal returns for 2025/26; explicitly dates 2026/27 advance payments | A £2,400 prior-year balance and first £1,200 payment on account produce a £3,600 January bill. The second £1,200 is normally due in July. Shows registration, UTR, filing and payment as separate steps, and amendment, penalty and adviser routes. |

## Source verification · 26 November 2026

The original audit was on 21 November. All 26 distinct canonical source URLs returned HTTP 200 on 26 November. Rechecked the current HMRC rates, Scottish bands, Welsh budget parity, emergency codes, filing and payment dates, amendments and penalties. All worked amounts and the tax years in the examples remain the same.

- PAYE and employee Class 1 example basis. [HMRC annual rates and allowances](https://www.gov.uk/government/publications/rates-and-allowances-income-tax/income-tax-rates-and-allowances-current-and-past) · [HMRC 2026/27 payroll figures](https://www.gov.uk/guidance/rates-and-thresholds-for-employers-2026-to-2027) · [Scottish Budget tax policy](https://www.gov.scot/publications/scottish-budget-2026-2027/pages/4/) · [Welsh Government final budget](https://www.gov.wales/sites/default/files/publications/2026-01/final-budget-2026-2027-report.pdf) · [HMRC employee expenses](https://www.gov.uk/tax-relief-for-employees/overview)
- Filing eligibility, registration, deadline and payment method. [Who needs to file](https://www.gov.uk/check-if-you-need-tax-return) · [Registration](https://www.gov.uk/register-for-self-assessment) · [2025/26 deadlines](https://www.gov.uk/self-assessment-tax-returns/deadlines) · [Online filing](https://www.gov.uk/log-in-file-self-assessment-tax-return) · [Card payment reference](https://www.gov.uk/pay-self-assessment-tax-bill/by-debit-or-credit-card-online)
- Payments on account, amendments, penalties and filing exceptions. [HMRC payments on account](https://www.gov.uk/understand-self-assessment-bill/payments-on-account) · [Taxes Management Act 1970, section 59A](https://www.legislation.gov.uk/ukpga/1970/9/section/59A) · [Section 9ZA](https://www.legislation.gov.uk/ukpga/1970/9/section/9ZA) · [HMRC corrections](https://www.gov.uk/self-assessment-tax-returns/corrections) · [MTD](https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax/using-making-tax-digital-for-income-tax-for-the-first-time)
- All five PAYE drafts now identify the `NONCUM` marker some payroll packages show. [HMRC emergency codes](https://www.gov.uk/tax-codes/emergency-tax-codes)
- All five Self Assessment drafts now cover returning filers who must notify HMRC, the three months from the date of HMRC's late-registration notice, and the separate payment deadline. They also clarify who should check for an already applicable MTD start date, possible interest after reducing advance payments too far, and the need to file promptly if payment is difficult. The 2025/26 calculation notes use the same strict “more than 80%” wording as HMRC's public payments guide. [Deadlines](https://www.gov.uk/self-assessment-tax-returns/deadlines) · [MTD](https://www.gov.uk/guidance/use-making-tax-digital-for-income-tax/using-making-tax-digital-for-income-tax-for-the-first-time) · [Payments on account](https://www.gov.uk/understand-self-assessment-bill/payments-on-account) · [SA110 notes](https://www.gov.uk/government/publications/self-assessment-tax-calculation-summary-sa110) · [Penalties](https://www.gov.uk/self-assessment-tax-returns/penalties)

## Validation

- `python3 -B docs/editorial/personal-tax/validate-foundations.py` passed all three checks. These cover independent arithmetic, date and amount parity in the five locales, DRAFT identity, file and canonical hashes, source and locator parity, and localized links.
- The production Java Guide reader and quality gate read all 10 drafts. The Java canonical digests matched all 10 handoff hashes. Zero warnings or blocking findings.
- Full backend `mvn clean verify` finished with 1,242 passed and 53 skipped. The 50 publisher unit tests passed. Offline Money manifest pack/check accepted both slugs and all ten variants. The ZIP remains local.
- Rendered the 10 drafts with the existing frontend static renderer at 320 px. Canonicals, hreflang, locale links, evidence anchors and Urdu RTL rendered as expected. This preview used draft fixtures and did not create public pages.

**GO for owner review. Publication pending.** Recheck time-sensitive rates, HMRC filing and MTD instructions at release. Review the multilingual wording, especially Urdu and Hindi. No independent human or native-speaker sign-off has taken place. Publish both foundation Guides together because their locale links point to each other. The calculator specification is a separate approval decision and has no runtime implementation.

## Release after approval

1. Approve the reviewed Guide files and merge after the required backend CI check. On the merged revision, confirm the same ten file and canonical digests before using the checked local ZIP. Require successful CI for that exact workflow revision.
2. With authorization, upload the ZIP to the existing private, versioned release bucket and verify the exact object version against the ZIP digest. Dispatch **Publish reviewed guides** with `release=manifest`, `package_sha256`, `manifest_sha256` and `package_version` for this Money batch only.
3. Compare the three inputs in the workflow summary with the approval evidence. The owner approves the protected Production job. The runner checks for duplicates, takes verified backups before staging and publication, and verifies draft revisions and content hashes through the existing authenticated workflow.
4. Record the verified report digest, backup references and publication and revision IDs. Verify all ten public API variants, Money listings and sitemap entries. Use the separate protected frontend release for the static pages, then verify the ten routes, canonical and hreflang metadata, locale navigation and Urdu RTL.

Use the existing [publishing and recovery runbook](../../../deploy/PUBLISHING.md).
