"""Check sponsored-employment drafts and their numerical example in all locales."""

from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[3] / "deploy" / "publish"))
from promote import canonical_form

LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
SEARCH = "find-uk-jobs-with-visa-sponsorship"
OFFER = "check-skilled-worker-job-offer"
PATHWAYS = "uk-options-after-graduation"
GRADUATE = "graduate-visa-uk"
LINKS = {
    SEARCH: {OFFER, PATHWAYS, GRADUATE, "skilled-worker-sponsorship-cos"},
    OFFER: {SEARCH, PATHWAYS, GRADUATE, "skilled-worker-occupations-salary"},
}


def shape(guide):
    return [(item["key"], [(s["sourceKey"], s["locator"]) for s in item["supports"]])
            for item in guide["evidence"]]


class SponsoredEmploymentDraftTest(unittest.TestCase):
    def test_scope_hashes_sources_and_links(self):
        for slug, linked_slugs in LINKS.items():
            group = HERE / "drafts" / slug
            manifest = json.loads((group / "EDITORIAL-MANIFEST.json").read_bytes())
            self.assertEqual((manifest["slug"], manifest["category"], manifest["status"]),
                             (slug, "work-visa", "DRAFT"))
            self.assertEqual(manifest["sourceLocale"], "zh-CN")
            self.assertEqual(manifest["canonicalization"], "guide-content-v1")
            self.assertCountEqual([row["locale"] for row in manifest["variants"]], LOCALES)
            source = json.loads((group / "zh-CN.json").read_bytes())
            for row in manifest["variants"]:
                locale = row["locale"]
                with self.subTest(slug=slug, locale=locale):
                    self.assertEqual(row["path"], f"{locale}.json")
                    raw = (group / row["path"]).read_bytes()
                    guide = json.loads(raw)
                    self.assertEqual((guide["slug"], guide["category"], guide["status"]),
                                     (slug, "work-visa", "DRAFT"))
                    self.assertIsNone(guide["publishedAt"])
                    self.assertEqual(row["sha256"], sha256(raw).hexdigest())
                    self.assertEqual(row["contentSha256"], sha256(canonical_form(guide).encode()).hexdigest())
                    self.assertEqual(guide["sources"], source["sources"])
                    self.assertEqual(shape(guide), shape(source))
                    body = guide["content"]
                    self.assertTrue(body.startswith("# " + guide["title"] + "\n"))
                    self.assertTrue(body.splitlines()[2].endswith(manifest["sourcesVerifiedOn"]))
                    self.assertCountEqual(re.findall(r"\(#guide-evidence-([a-z-]+)\)", body),
                                          [item["key"] for item in guide["evidence"]])
                    self.assertCountEqual(re.findall(r"\]\((/[^)]+)\)", body),
                                          [f"/{locale}/work-visa/{target}" for target in linked_slugs])
                    self.assertNotRegex(body, r"[\u202a-\u202e\ufffd]")
                    if locale in ("en", "hi", "ur"):
                        self.assertNotRegex(body, r"[\u3400-\u9fff]")

    def test_salary_floor_going_rate_and_combined_permission_examples(self):
        standard, entrant = 41700, 33400
        full_going_rate, basic, bonus = 50000, 34900, 1100
        entrant_rate = int(full_going_rate * Decimal("0.70"))
        requirement = max(entrant, entrant_rate)
        self.assertEqual((max(standard, full_going_rate), requirement, requirement - basic), (50000, 35000, 100))
        self.assertGreater(basic + bonus, entrant_rate)
        self.assertGreater(18 + 3 * 12, 4 * 12)
        amounts = (standard, entrant, full_going_rate, basic, bonus, entrant_rate, requirement - basic)
        for locale in LOCALES:
            guide = json.loads((HERE / "drafts" / OFFER / f"{locale}.json").read_bytes())
            with self.subTest(locale=locale):
                for marker in ("37.5", "70%", "18", "26", "Tier 2", *(f"£{n:,}" for n in amounts)):
                    self.assertIn(marker, guide["content"])


if __name__ == "__main__":
    unittest.main()
