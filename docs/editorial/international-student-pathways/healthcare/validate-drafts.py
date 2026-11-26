"""Check editorial hashes, locales, eligibility dates and example parity."""

from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import unittest

PROGRAMME = Path(__file__).resolve().parent
sys.path.insert(0, str(PROGRAMME.parents[3] / "deploy" / "publish"))
from promote import canonical_form

LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
HEALTH = "health-and-care-worker-visa"
CARE = "adult-social-care-worker-sponsorship"
PUBLIC_LINKS = {
    HEALTH: {("work-visa", "skilled-worker-switch-extend-change-employer"),
             ("work-visa", "skilled-worker-settlement-ilr")},
    CARE: {("work-visa", "uk-options-after-graduation")},
}
CARE_DATES = {
    "zh-CN": ("2025 年 7 月 22 日", "2028 年 7 月 22 日", "2024 年 3 月 11 日", "三个月"),
    "zh-TW": ("2025 年 7 月 22 日", "2028 年 7 月 22 日", "2024 年 3 月 11 日", "三個月"),
    "en": ("22 July 2025", "22 July 2028", "11 March 2024", "three months"),
    "ur": ("22 جولائی 2025", "22 جولائی 2028", "11 مارچ 2024", "تین ماہ"),
    "hi": ("22 जुलाई 2025", "22 जुलाई 2028", "11 मार्च 2024", "तीन महीने"),
}


def draft(slug, locale):
    return json.loads((PROGRAMME / "drafts" / slug / f"{locale}.json").read_bytes())


def evidence_shape(guide):
    return [(item["key"], [(s["sourceKey"], s["locator"]) for s in item["supports"]])
            for item in guide["evidence"]]


class HealthcareDraftTest(unittest.TestCase):
    def test_immutable_five_locale_handoff(self):
        for slug in (HEALTH, CARE):
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
                    peer = CARE if slug == HEALTH else HEALTH
                    expected_links = {f"/{locale}/{domain}/{target}"
                                      for domain, target in PUBLIC_LINKS[slug] | {("work-visa", peer)}}
                    self.assertEqual(set(re.findall(r"\]\((/[^)]+)\)", body)), expected_links)

    def test_rule_markers_survive_localization(self):
        for locale in LOCALES:
            with self.subTest(locale=locale):
                care = draft(CARE, locale)["content"]
                for value in (*CARE_DATES[locale], "SW 6.1B(a)", "SW 6.1B(b)", "SW 6.1C",
                              "6135", "6136", "6145", "6146", "£25,000", "£12.82", "CQC", "CoS"):
                    self.assertIn(value, care)
                health = draft(HEALTH, locale)["content"]
                for value in ("GMC", "NMC", "HCPC", "PLAB", "SOC", "£25,000", "CoS", "IHS"):
                    self.assertIn(value, health)


if __name__ == "__main__":
    unittest.main()
