"""Check the editorial examples and five-locale handoff. This does not implement tax calculators."""

from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / "deploy" / "publish"))
from promote import canonical_form

LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
PAYE = "uk-paye-tax-codes-national-insurance"
SA = "uk-self-assessment-register-file-pay"
NINO = "national-insurance-number-newcomers"


def draft(slug, locale):
    return json.loads((ROOT / "foundations" / slug / (locale + ".json")).read_bytes())


class FoundationTest(unittest.TestCase):
    def test_manifest_sources_evidence_and_links(self):
        for slug in (PAYE, SA):
            group = ROOT / "foundations" / slug
            manifest = json.loads((group / "EDITORIAL-MANIFEST.json").read_bytes())
            self.assertEqual(manifest["slug"], slug)
            self.assertEqual(manifest["canonicalization"], "guide-content-v1")
            self.assertEqual(manifest["sourceLocale"], "zh-CN")
            variants = manifest["variants"]
            self.assertEqual(sorted(row["locale"] for row in variants), sorted(LOCALES))
            source = draft(slug, "zh-CN")

            def evidence_shape(guide):
                return [
                    (item["key"], [(s["sourceKey"], s["locator"], s["excerpt"]) for s in item["supports"]])
                    for item in guide["evidence"]
                ]

            for row in variants:
                locale = row["locale"]
                with self.subTest(slug=slug, locale=locale):
                    self.assertEqual(row["path"], locale + ".json")
                    raw = (group / row["path"]).read_bytes()
                    guide = json.loads(raw)
                    self.assertEqual(row["sha256"], sha256(raw).hexdigest())
                    self.assertEqual(row["contentSha256"], sha256(canonical_form(guide).encode()).hexdigest())
                    self.assertEqual((guide["slug"], guide["category"], guide["status"]), (slug, "money", "DRAFT"))
                    self.assertIsNone(guide["publishedAt"])
                    self.assertEqual(guide["sources"], source["sources"])
                    self.assertEqual(evidence_shape(guide), evidence_shape(source))
                    body = guide["content"]
                    self.assertTrue(body.startswith("# " + guide["title"] + "\n"))
                    anchors = set(re.findall(r"\(#guide-evidence-([a-z0-9-]+)\)", body))
                    self.assertEqual(anchors, {item["key"] for item in guide["evidence"]})
                    expected = {f"/{locale}/money/{SA}", f"/{locale}/money/{NINO}"} if slug == PAYE else {f"/{locale}/money/{PAYE}"}
                    self.assertEqual(set(re.findall(r"\]\((/[^)]+)\)", body)), expected)
                    self.assertNotRegex(body, r"@L|[\u202a-\u202e\ufffd]")
                    if locale in ("hi", "ur"):
                        self.assertNotRegex(guide["title"] + guide["summary"] + body, r"[\u3400-\u9fff]")

    def test_payslip_and_expense_examples(self):
        taxable = 3000 * 12 - 12570
        main_tax = taxable * Decimal(".20")
        scottish_start = 3967
        scottish_basic = 16956 - scottish_start
        scottish_middle = taxable - scottish_start - scottish_basic
        scottish_tax = scottish_start * Decimal(".19") + scottish_basic * Decimal(".20") + scottish_middle * Decimal(".21")
        ni = (3000 - 1048) * Decimal(".08")
        relief = 60 * Decimal(".20")
        self.assertEqual((taxable, main_tax, scottish_tax, ni, relief), (23430, 4686, Decimal("4711.07"), Decimal("156.16"), 12))
        amounts = (
            f"£{main_tax:,.2f}", f"£{scottish_tax:,.2f}", f"£{ni:,.2f}",
            f"£{3000 - main_tax / 12 - ni:,.2f}", f"£{3000 - scottish_tax / 12 - ni:,.2f}",
            "£12 of Income Tax",
        )
        for locale in LOCALES:
            body = draft(PAYE, locale)["content"]
            for amount in amounts[:5]:
                self.assertIn(amount, body, locale)
            self.assertIn("£60", body, locale)
        self.assertIn(amounts[-1], draft(PAYE, "en")["content"])

    def test_self_assessment_example_and_dates(self):
        dates = {
            "en": ("5 October 2026", "31 October 2026", "31 January 2027", "31 July 2027", "31 January 2028"),
            "zh-CN": ("2026 年 10 月 5 日", "2026 年 10 月 31 日", "2027 年 1 月 31 日", "2027 年 7 月 31 日", "2028 年 1 月 31 日"),
            "zh-TW": ("2026 年 10 月 5 日", "2026 年 10 月 31 日", "2027 年 1 月 31 日", "2027 年 7 月 31 日", "2028 年 1 月 31 日"),
            "ur": ("5 اکتوبر 2026", "31 اکتوبر 2026", "31 جنوری 2027", "31 جولائی 2027", "31 جنوری 2028"),
            "hi": ("5 अक्टूबर 2026", "31 अक्टूबर 2026", "31 जनवरी 2027", "31 जुलाई 2027", "31 जनवरी 2028"),
        }
        balance = 2400
        first = balance // 2
        self.assertEqual((first, balance + first), (1200, 3600))
        for locale in LOCALES:
            body = draft(SA, locale)["content"]
            for expected in (*dates[locale], f"£{balance:,}", f"£{first:,}", f"£{balance + first:,}", "2025/26", "2026/27"):
                self.assertIn(expected, body, locale)


if __name__ == "__main__":
    unittest.main()
