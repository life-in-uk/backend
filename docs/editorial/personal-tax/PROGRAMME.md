# UK Personal Tax programme

## Catalogue audit · 21 November 2026

The public Guide API returned the same 73 slugs in zh-CN, zh-TW, en, ur and hi. The audit compared all five slug sets and inspected the English detail bodies for the four Money Guides and other tax-related matches. The backend editorial tree also contains the separate Council Tax revision proposal. No public Guide takes readers through personal employment tax or Self Assessment, and the two proposed Foundation slugs are absent from all five public listings.

| Existing coverage | Reuse |
|---|---|
| `national-insurance-number-newcomers`, Money | Keep the NI number application and identity steps here. The new employment Guide explains deductions and links back. |
| `renting-council-tax` and `renting-moving-home-utilities-checklist`, Renting | Keep Council Tax and move notifications here. England is the main scope. Signpost devolved rules and Northern Ireland domestic rates where relevant. |
| `open-uk-bank-account-new-arrival` and `uk-credit-history-and-credit-score`, Money | Banking access and credit history. Keep these separate from savings interest, ISA rules and HMRC reporting. |
| `sending-money-abroad-safely`, Money | Use for transfers. Residence, income, gains and double-tax relief need separate coverage. |
| Skilled Worker and Partner Visa income Guides | These explain immigration evidence and salary tests. Link only where the payslip or tax record is relevant. |

Use existing Money category routes. Each public Guide covers one reader task end to end. Preserve the Renting and NI-number articles. The programme covers individuals. Corporation Tax, company accounts and VAT, employer payroll operations and business taxation are outside scope. Self-employment appears only where it affects the individual's reporting route.

## Taxonomy and phased backlog

| Phase | Proposed Guide or module | Reader task and verification scope |
|---|---|---|
| 1 · Foundations | `uk-paye-tax-codes-national-insurance` | Read a payslip, estimate tax and correct a PAYE code. UK Class 1 employee NI, Scottish employment bands, Welsh taxpayer classification and England/NI bands. |
| 1 · Foundations | `uk-self-assessment-register-file-pay` | Decide if a return is needed, register or reactivate, find UTR, use HMRC's online service, pay, amend and respond to a penalty. UK deadlines; identify separate MTD and non-resident filing routes. Add a DIY vs adviser decision to the journey. |
| 2 · Property income | `uk-personal-rental-income-tax` | Compute personally owned UK property profit, separate repairs from improvements, compare the property allowance with actual costs where eligible, handle residential finance costs and unused relief. Reconcile Scottish income and other regional rules for the year covered. Report via the proper HMRC property pages. |
| 2 · Property income | `uk-rental-losses-and-joint-ownership` | Carry forward eligible UK property business losses against the relevant future profits, separate overseas property, consider joint ownership and spouses/civil partners, and report via the property pages. Cover cessation and complex exceptions only once verified. |
| 2 · Gains | `uk-capital-gains-home-and-rental-property` | Establish disposal date, proceeds, basis, improvements and costs; explain Private Residence Relief and the UK property reporting route and payment clock. Distinguish UK-resident and non-resident reporting and later Self Assessment reconciliation. |
| 2 · Gains | `uk-capital-gains-shares-and-losses` | Identify taxable investments, shares matching and pooling, current-year and carried-forward capital losses, claiming and recordkeeping. Demonstrate shares and a simple chattel or other investment only within verified examples. |
| 3 · Investing | `uk-dividend-and-savings-tax` | Identify taxable dividends and interest, rate stacking, relevant allowances and tax wrappers. Show how to check PAYE coding or which return pages to use. Separate UK-wide rules from Scottish earned-income bands. |
| 3 · Investing | `uk-isas-and-personal-pension-tax` | Separate tax-free wrapper income from contribution rules, pension tax relief and taxable withdrawals. Link to specialist review for annual allowance, taper, carry-forward, tax-free lump sums and overseas transfers. |
| 4 · Cross-border | `uk-tax-residence-and-overseas-income` | Use the statutory residence test and split-year signposts, identify overseas income and gains, explain the applicable foreign income and gains regime and reporting pages. Flag transition and residence complexity. |
| 4 · Cross-border | `uk-foreign-tax-credit-and-double-taxation` | Distinguish HMRC credit claims from treaty relief or repayment abroad. Explain comparable tax, evidence and limits via one verified simple example. |
| 4 · Estate | `uk-inheritance-tax-and-gifts` | Calculate simple estates and standalone gift scenarios, explain exemptions, the gift lookback, estate reporting and payment routes. Distinguish domicile-era and residence-based rules by date. |
| 4 · Estate | `uk-inheritance-tax-home-and-estate-reliefs` | Explain passing a home to qualifying descendants, transferable allowances and taper with verified examples. Defer trusts, complex gifts, agricultural/business relief, foreign estates and disputed valuations to specialist advice. |
| 5 · Adjacent personal taxes | Existing Council Tax Guide and UK property-purchase tax signposts | Reuse Council Tax. If later expanded, separate SDLT in England/NI, LBTT in Scotland and LTT in Wales. No purchase-tax calculator in the initial scope. |
| 5 · Compliance | `uk-permanent-tax-records-and-getting-help` if demand warrants | Build on Foundation 2. Records, missing UTR, voluntary disclosure and escalation; choosing a suitably qualified adviser, agent authorization and checking returns. Avoid duplication with first filing FAQ. |

Each future Guide requires a date-stamped source register. Start with current HMRC manuals and GOV.UK service pages, then check legislation and devolved authorities for the precise point in time. Bind examples, rates, allowances and deadlines to their tax year or chargeable event date. Separate enacted measures in force, enacted future changes and announcements. State relief eligibility, order of operations, claim deadline and filing location. Build evidence and totals from zh-CN, preserve them across all five versions and confirm all localized links.

**Loss terminology.** Capital losses generally offset eligible gains, including qualifying carried-forward amounts subject to the CGT rules. A property business loss follows its own income-tax rules. Mortgage finance cost relief for an individual residential landlord is a separate tax reduction, with limits and carry-forward rules. Foreign Tax Credit Relief is a different mechanism again. Drafts must show exactly which amount is reduced and how it is reported.

## Release sequence and gates

1. Owner reviews these two five-locale foundation drafts and the calculator specification. Focus on source parity, worked arithmetic, wording and fluent Urdu/Hindi review.
2. Recheck the dates, NI thresholds, Self Assessment deadline and MTD rules when preparing a release. Use the existing manifest pack/check and protected Money publication path. Resolve any duplicate or disputed scope before dispatch.
3. Deliver one small thematic PR at a time. Run the existing Java reader, evidence and quality gates, canonical hash checks, worked-example tests and relevant frontend rendering checks. Preserve explicit date qualifiers in translations. Record reviewed artifacts and source-backed editorial findings separately from GitHub Production approval.
4. Ask for explicit architecture approval before any calculator code. No calculator implementation is included here.
