"""Check editorial hashes, locales, eligibility dates and example parity."""

from hashlib import sha256
import json
from decimal import Decimal
from pathlib import Path
import re
import sys
import unittest

PROGRAMME = Path(__file__).resolve().parent
sys.path.insert(0, str(PROGRAMME.parents[2] / "deploy" / "publish"))
from promote import canonical_form

LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
FIRST = "uk-first-credit-card"
PROMO = "uk-zero-percent-credit-cards"
REWARDS = "uk-credit-card-cashback-and-rewards"
LOAN = "uk-personal-loans-apply-and-compare"
CREDIT_HISTORY = "uk-credit-history-and-credit-score"
BANK_ACCOUNT = "open-uk-bank-account-new-arrival"
PUBLIC_LINKS = {
    FIRST: {CREDIT_HISTORY, BANK_ACCOUNT},
    PROMO: {CREDIT_HISTORY, FIRST},
    REWARDS: {CREDIT_HISTORY, FIRST},
    LOAN: {CREDIT_HISTORY, BANK_ACCOUNT},
}


def draft(slug, locale):
    return json.loads((PROGRAMME / "drafts" / slug / f"{locale}.json").read_bytes())


def evidence_shape(guide):
    return [(item["key"], [(s["sourceKey"], s["locator"]) for s in item["supports"]])
            for item in guide["evidence"]]


class ConsumerCreditDraftTest(unittest.TestCase):
    def test_immutable_five_locale_handoff(self):
        for slug, links in PUBLIC_LINKS.items():
            folder = PROGRAMME / "drafts" / slug
            manifest = json.loads((folder / "EDITORIAL-MANIFEST.json").read_bytes())
            self.assertEqual((manifest["slug"], manifest["category"], manifest["status"]), (slug, "money", "DRAFT"))
            self.assertEqual((manifest["sourceLocale"], manifest["canonicalization"]), ("zh-CN", "guide-content-v1"))
            self.assertCountEqual([variant["locale"] for variant in manifest["variants"]], LOCALES)
            source = draft(slug, "zh-CN")
            for variant in manifest["variants"]:
                locale = variant["locale"]
                with self.subTest(slug=slug, locale=locale):
                    self.assertEqual(variant["path"], f"{locale}.json")
                    raw = (folder / variant["path"]).read_bytes()
                    guide = json.loads(raw)
                    self.assertEqual((guide["slug"], guide["category"], guide["status"]), (slug, "money", "DRAFT"))
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
                    expected = {f"/{locale}/money/{target}" for target in links}
                    self.assertEqual(set(re.findall(r"\]\((/[^)]+)\)", body)), expected)

    def check_numbers(self, slug, locale, expected):
        lines = draft(slug, locale)["content"].splitlines()
        content = "\n".join([lines[0], *lines[3:]])
        actual = set(re.findall(r"£\d+(?:,\d{3})*|[\d.]+%|(?<![\w-])\d+(?![\w-])", content))
        self.assertEqual(actual, set(expected))

    def test_calculations_and_legal_amounts(self):
        pounds = lambda n: f"£{n:,.0f}"
        transfer = Decimal(2400)
        fee_12 = transfer * Decimal("0.03")
        fee_6 = transfer * Decimal("0.015")
        cap = min(Decimal(3000) * Decimal("0.01"), Decimal(25))
        net = cap - 24
        foreign_fee = Decimal(200) * Decimal("0.03")
        total_24, total_36 = 140 * 24, 100 * 36
        for locale in LOCALES:
            with self.subTest(locale=locale):
                self.check_numbers(FIRST, locale,
                                   ["75", "75", "£100", "£30,000", "75", "£50", "£600"])
                self.check_numbers(PROMO, locale, ["0%", "0%", "0%", "£2,400", "3%", pounds(fee_12),
                                   pounds(transfer + fee_12), "12", pounds((transfer + fee_12) / 12),
                                   "1.5%", pounds(fee_6), pounds(transfer + fee_6), "6",
                                   pounds((transfer + fee_6) / 6), pounds(fee_12 - fee_6)])
                self.check_numbers(REWARDS, locale, ["£3,000", "1%", "£30", pounds(cap), "£24",
                                   pounds(net), "£200", "3%", pounds(foreign_fee), pounds(foreign_fee - net)])
                self.check_numbers(LOAN, locale, ["51%", "£3,000", "£140", "24", pounds(total_24),
                                   pounds(total_24 - 3000), "£100", "36", pounds(total_36),
                                   pounds(total_36 - 3000), "£40", pounds(total_36 - total_24)])


if __name__ == "__main__":
    unittest.main()
