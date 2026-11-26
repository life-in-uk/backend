"""Check editorial hashes, locales, eligibility dates and example parity."""

from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import unittest

PROGRAMME = Path(__file__).resolve().parent
sys.path.insert(0, str(PROGRAMME.parents[2] / "deploy" / "publish"))
from promote import canonical_form

LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
HUB = "uk-options-after-graduation"
GRADUATE = "graduate-visa-uk"
PUBLIC_LINKS = {
    HUB: {
        ("work-visa", "skilled-worker-sponsorship-cos"),
        ("family-visa", "partner-visa-routes-overview"),
        ("settlement", "settlement-long-residence"),
        ("money", "national-insurance-number-newcomers"),
        ("money", "uk-paye-tax-codes-national-insurance"),
    },
    GRADUATE: {("work-visa", "skilled-worker-occupations-salary")},
}
CUTOFFS = {
    "zh-CN": ("2026 年 12 月 31 日", "2027 年 1 月 1 日"),
    "zh-TW": ("2026 年 12 月 31 日", "2027 年 1 月 1 日"),
    "en": ("31 December 2026", "1 January 2027"),
    "ur": ("31 دسمبر 2026", "1 جنوری 2027"),
    "hi": ("31 दिसंबर 2026", "1 जनवरी 2027"),
}


def draft(slug, locale):
    return json.loads((PROGRAMME / "drafts" / slug / f"{locale}.json").read_bytes())


def evidence_shape(guide):
    return [(item["key"], [(s["sourceKey"], s["locator"]) for s in item["supports"]])
            for item in guide["evidence"]]


class StudentPathwaysDraftTest(unittest.TestCase):
    def test_immutable_five_locale_handoff(self):
        for slug in (HUB, GRADUATE):
            folder = PROGRAMME / "drafts" / slug
            manifest = json.loads((folder / "EDITORIAL-MANIFEST.json").read_bytes())
            self.assertEqual((manifest["slug"], manifest["category"], manifest["status"]), (slug, "work-visa", "DRAFT"))
            self.assertEqual((manifest["sourceLocale"], manifest["canonicalization"]), ("zh-CN", "guide-content-v1"))
            self.assertCountEqual([variant["locale"] for variant in manifest["variants"]], LOCALES)
            source = draft(slug, "zh-CN")
            for variant in manifest["variants"]:
                locale = variant["locale"]
                with self.subTest(slug=slug, locale=locale):
                    self.assertEqual(variant["path"], f"{locale}.json")
                    raw = (folder / variant["path"]).read_bytes()
                    guide = json.loads(raw)
                    self.assertEqual((guide["slug"], guide["category"], guide["status"]), (slug, "work-visa", "DRAFT"))
                    self.assertIsNone(guide["publishedAt"])
                    self.assertEqual(variant["sha256"], sha256(raw).hexdigest())
                    self.assertEqual(variant["contentSha256"], sha256(canonical_form(guide).encode()).hexdigest())
                    self.assertEqual(guide["sources"], source["sources"])
                    self.assertEqual(evidence_shape(guide), evidence_shape(source))
                    day = manifest["sourcesVerifiedOn"]
                    self.assertTrue(all(s["accessedAt"].startswith(day) for s in guide["sources"]))
                    body = guide["content"]
                    self.assertEqual(body.splitlines()[0], "# " + guide["title"])
                    self.assertTrue(body.splitlines()[2].endswith(day))
                    self.assertNotRegex(body, r"[\u202a-\u202e\ufffd]")
                    if locale in ("hi", "ur"):
                        self.assertNotRegex(body, r"[\u3400-\u9fff]")
                    anchors = re.findall(r"\(#guide-evidence-([a-z-]+)\)", body)
                    self.assertCountEqual(anchors, [item["key"] for item in guide["evidence"]])
                    peer = GRADUATE if slug == HUB else HUB
                    expected_links = {f"/{locale}/{domain}/{target}"
                                      for domain, target in PUBLIC_LINKS[slug] | {("work-visa", peer)}}
                    self.assertEqual(set(re.findall(r"\]\((/[^)]+)\)", body)), expected_links)

    def test_graduate_amounts_and_cutoff(self):
        fee = Decimal(937)
        annual_charge = Decimal(1035)
        amounts = (f"£{fee:,.0f}", f"£{annual_charge * Decimal('1.5'):,.2f}", f"£{annual_charge * 2:,.0f}",
                   f"£{annual_charge * 3:,.0f}", f"£{fee + annual_charge * 2:,.0f}")
        for locale in LOCALES:
            with self.subTest(locale=locale):
                body = draft(GRADUATE, locale)["content"]
                for value in (*CUTOFFS[locale], *amounts, "18", "3C", "ST 26.6"):
                    self.assertIn(value, body)
                example = draft(HUB, locale)["content"]
                for value in ("2", "3", "5", "10", "3C"):
                    self.assertRegex(example, rf"(?<!\d){value}(?!\d)")


if __name__ == "__main__":
    unittest.main()
