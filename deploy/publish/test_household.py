"""Draft-only Household release boundary; fixtures contain no editorial content."""

from contextlib import nullcontext
from hashlib import sha256
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile

import ci_release as ci
import household


class HouseholdDraftTests(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.manifest = {"proposedCategory": household.CATEGORY, "status": "EDITORIAL_DRAFT",
                         "articleCount": 7, "variantCount": 35, "articles": []}
        for slug in sorted(household.SLUGS):
            records = {}
            for locale in ci.LOCALES:
                key = f"guides/{slug}/{locale}.json"
                guide = {"slug": slug, "category": household.CATEGORY, "status": "DRAFT",
                         "publishedAt": None, "updatedAt": household.IDENTITY.valid_from,
                         "title": "Synthetic title", "summary": "Synthetic summary",
                         "content": locale, "sources": [], "evidence": []}
                raw = ci.encoded(guide)
                file = self.root / key
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(raw)
                records[locale] = {"file": key, "fileSha256": sha256(raw).hexdigest(),
                                   "contentSha256": ci.digest(household.canonical_form(guide).encode())}
            self.manifest["articles"].append({"slug": slug, "locales": records,
                                              "sourceContentSha256": records["zh-CN"]["contentSha256"]})

    def load(self):
        raw = ci.encoded(self.manifest)
        (self.root / household.MANIFEST).write_bytes(raw)
        with patch.object(household, "MANIFEST_SHA256", ci.digest(raw)):
            return household.load_package(self.root, ci.read_json)

    def test_pinned_manifest_and_all_35_guide_hashes(self):
        package = self.load()
        self.assertEqual(sum(map(len, package.values())), 35)
        with self.assertRaisesRegex(ci.Refused, "manifest does not match"):
            household.load_package(self.root, ci.read_json)
        record = self.manifest["articles"][0]["locales"]["en"]
        with (self.root / record["file"]).open("ab") as guide:
            guide.write(b" ")
        with self.assertRaisesRegex(ci.Refused, "file hash"):
            self.load()
        record["fileSha256"] = ci.digest((self.root / record["file"]).read_bytes())
        record["contentSha256"] = "b" * 64
        with self.assertRaisesRegex(ci.Refused, "canonical content hash"):
            self.load()

    def test_unknown_slug_and_hidden_archive_members_are_rejected(self):
        self.manifest["articles"][0]["slug"] = "university-guide"
        with self.assertRaisesRegex(ci.Refused, "allowlist"):
            self.load()
        zip_path = self.root / "package.zip"
        with ZipFile(zip_path, "w") as archive:
            for name in household.package_names() | {"reviewer-notes.txt"}:
                archive.writestr(name, b"")
        with self.assertRaisesRegex(ci.Refused, "archive member"):
            ci.unpack(zip_path, self.root, household.package_names())

    def test_household_stage_requires_backup_and_draft_identity(self):
        package = self.load()
        fake_batches = {
            slug: {"batchId": str(index), "locales": dict.fromkeys(ci.LOCALES)}
            for index, slug in enumerate(sorted(package))
        }
        session = Mock()

        def confirmed(_, slug, *args, statuses, reviewed, identity):
            self.assertEqual(statuses, {"DRAFT"})
            self.assertFalse(reviewed)
            self.assertEqual(identity, household.IDENTITY)
            return fake_batches[slug]

        with patch.multiple(
            ci, public_inventory=Mock(return_value={}), owner_session=Mock(return_value=nullcontext(session)),
            read_state=Mock(return_value={}), prepare_import=Mock(), verify_visibility=Mock(),
            backup=Mock(side_effect=ci.Refused("backup failed")), import_drafts=Mock(return_value=fake_batches),
            inspect_batch_detail=Mock(side_effect=confirmed), batch_path=Mock(), save_receipt=Mock(),
        ):
            with self.assertRaisesRegex(ci.Refused, "backup failed"):
                ci.stage(package, "a" * 64, self.root, household_drafts=True)
            ci.import_drafts.assert_not_called()
            ci.backup.side_effect = None
            ci.backup.return_value = "backup-ref"
            ci.stage(package, "a" * 64, self.root, household_drafts=True)
            ci.backup.assert_called_with("pre-household-import")
            ci.owner_session.assert_called_with("DRAFT")
            ci.import_drafts.assert_called_once_with(session, package, identity=household.IDENTITY)
            self.assertEqual(ci.save_receipt.call_args.kwargs["prefix"], "household")

    def test_household_public_baseline_keeps_published_education(self):
        school = sorted(ci.SLUGS)[0]
        guide = {"category": "education", "slug": school}
        sitemap = ('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                   f'<url><loc>https://lifeinuk.info/en/education/{school}</loc></url></urlset>').encode()
        public_rows = [guide]

        def response(path, status):
            if path.startswith(("/api/owner/", "/api/editorial/")):
                self.assertEqual(status, 404)
                return b""
            if path == "/sitemap.xml":
                return sitemap
            return ci.encoded(public_rows)

        with patch.object(ci, "edge", side_effect=response):
            baseline = ci.public_inventory(set(), category=household.CATEGORY, slugs=household.SLUGS)
            self.assertEqual(baseline["en"], [guide])
            self.assertEqual(baseline["sitemap"], [f"https://lifeinuk.info/en/education/{school}"])
            public_rows.append({"category": household.CATEGORY, "slug": sorted(household.SLUGS)[0]})
            with self.assertRaisesRegex(ci.Refused, "listing has unexpected scope"):
                ci.public_inventory(set(), category=household.CATEGORY, slugs=household.SLUGS)

    def test_education_publish_refuses_household_receipt(self):
        import_receipt = {"release": household.IDENTITY.release, "packageSha256": "a" * 64,
                          "backup": "backup-ref", "batches": dict.fromkeys(household.SLUGS)}
        with patch.object(ci, "fetch", return_value=ci.encoded(import_receipt)) as fetch:
            with self.assertRaisesRegex(ci.Refused, "scope or package digest"):
                ci.load_import_receipt(self.root, "a" * 64, "b" * 64)
            fetch.assert_called_once_with("receipts", "b" * 64, None, self.root / "import.json")
