"""Household import and publication boundary; fixtures contain no editorial content."""

from contextlib import nullcontext
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from uuid import UUID
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
                ci.stage(package, "a" * 64, self.root, release=ci.HOUSEHOLD)
            ci.import_drafts.assert_not_called()
            ci.backup.side_effect = None
            ci.backup.return_value = "backup-ref"
            ci.stage(package, "a" * 64, self.root, release=ci.HOUSEHOLD)
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
            self.assertNotEqual(path, "/sitemap.xml")
            return ci.encoded(public_rows)

        with patch.multiple(ci, edge=Mock(side_effect=response), local_reader=Mock(return_value=sitemap)):
            baseline = ci.public_inventory(set(), category=household.CATEGORY, slugs=household.SLUGS)
            ci.local_reader.assert_called_once_with("/sitemap.xml", 200)
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
            fetch.assert_called_once_with("receipts", "b" * 64, None, self.root / "import.json", prefix="education")


class HouseholdPublicationTests(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.receipts = {slug: {"batchId": str(UUID(int=index)), "sourceRevisionId": str(UUID(int=100 + index)),
                                "locales": {locale: {"contentHash": f"{index}-{locale}"} for locale in ci.LOCALES}}
                         for index, slug in enumerate(sorted(household.SLUGS), 1)}
        self.state = {"revisions": {slug: [row["sourceRevisionId"]] for slug, row in self.receipts.items()},
                      "publications": {}}

    def publish_mocks(self, session, *, backup):
        calls = {slug: 0 for slug in household.SLUGS}

        def batch_state(session, slug, guides, receipt, findings, history, verified_on, identity):
            self.assertEqual(identity, household.IDENTITY)
            calls[slug] += 1
            # Before reviews, after reviews, after approval, after publication.
            sequence = [("DRAFT", list(ci.LOCALES), None), ("READY", [], None), ("APPROVED", [], None),
                        ("PUBLISHED", [], str(UUID(int=200)))]
            return sequence[min(calls[slug], 4) - 1]

        return patch.multiple(
            ci, load_import_receipt=Mock(return_value=self.receipts), read_state=Mock(return_value=self.state),
            owner_session=Mock(return_value=nullcontext(session)), batch_state=Mock(side_effect=batch_state),
            public_inventory=Mock(return_value={"en": []}), verify_visibility=Mock(), backup=backup,
            credential=Mock(return_value="review-token"), save_receipt=Mock(return_value="c" * 64),
        )

    def test_household_publish_uses_household_scope_identity_backup_and_receipt_prefix(self):
        session = Mock()
        session.request.side_effect = lambda method, path, body=None, bearer=None: (
            [{"slug": slug, "batchId": row["batchId"]} for slug, row in self.receipts.items()]
            + [{"slug": "a-school-guide", "batchId": str(UUID(int=999))}]
            if path.endswith("/batches") else {})
        with self.publish_mocks(session, backup=Mock(return_value="backup-ref")):
            result = ci.publish(dict.fromkeys(household.SLUGS, {}), "a" * 64, "b" * 64, "2026-11-20", self.root,
                                release=ci.HOUSEHOLD)
            self.assertEqual(result, "c" * 64)
            ci.load_import_receipt.assert_called_once_with(self.root, "a" * 64, "b" * 64, ci.HOUSEHOLD)
            ci.owner_session.assert_called_once_with("APPROVAL")
            ci.backup.assert_called_once_with("pre-household-publish")
            ci.read_state.assert_called_with(slugs=household.SLUGS)
            for call in ci.public_inventory.call_args_list:
                self.assertEqual(call.kwargs, {"category": household.CATEGORY, "slugs": household.SLUGS})
            self.assertEqual(ci.public_inventory.call_args_list[-1].args, (set(household.SLUGS),))
            for call in ci.verify_visibility.call_args_list:
                self.assertEqual(call.kwargs, {"slugs": household.SLUGS, "category": household.CATEGORY})
            receipt, _, kind = ci.save_receipt.call_args.args
            self.assertEqual((kind, ci.save_receipt.call_args.kwargs), ("publications", {"prefix": "household"}))
            self.assertEqual(receipt["release"], household.IDENTITY.release)
            self.assertEqual(set(receipt["publications"]), household.SLUGS)
            posts = [call.args[1] for call in session.request.call_args_list if call.args[0] == "POST"]
            self.assertEqual(sum(path.endswith("/publish") for path in posts), 7)
            self.assertEqual(sum("/reviews/" in path for path in posts), 35)
            self.assertFalse(any(str(UUID(int=999)) in path for path in posts), "never touches other batches")

    def test_household_publish_backup_failure_prevents_review_and_publication_writes(self):
        session = Mock()
        session.request.return_value = [{"slug": slug, "batchId": row["batchId"]} for slug, row in self.receipts.items()]
        with self.publish_mocks(session, backup=Mock(side_effect=ci.Refused("backup failed"))):
            with self.assertRaisesRegex(ci.Refused, "backup failed"):
                ci.publish(dict.fromkeys(household.SLUGS, {}), "a" * 64, "b" * 64, "2026-11-20", self.root,
                           release=ci.HOUSEHOLD)
            self.assertEqual([call.args[0] for call in session.request.call_args_list], ["GET"])
            ci.credential.assert_not_called()
            ci.save_receipt.assert_not_called()

    def test_household_receipt_is_read_from_household_prefix_and_education_receipt_is_refused(self):
        batches = dict.fromkeys(household.SLUGS, {})
        receipt = {"release": household.IDENTITY.release, "packageSha256": "a" * 64, "backup": "b", "batches": batches}
        with patch.object(ci, "fetch", return_value=ci.encoded(receipt)) as fetch:
            self.assertEqual(ci.load_import_receipt(self.root, "a" * 64, "b" * 64, ci.HOUSEHOLD), batches)
            fetch.assert_called_once_with("receipts", "b" * 64, None, self.root / "import.json", prefix="household")
            for wrong in ({**receipt, "release": ci.RELEASE}, {**receipt, "batches": dict.fromkeys(ci.SLUGS, {})},
                          {**receipt, "packageSha256": "c" * 64}):
                fetch.return_value = ci.encoded(wrong)
                with self.subTest(wrong=wrong["release"]), self.assertRaisesRegex(ci.Refused, "scope or package digest"):
                    ci.load_import_receipt(self.root, "a" * 64, "b" * 64, ci.HOUSEHOLD)

    def test_release_families_are_disjoint(self):
        self.assertEqual((ci.EDUCATION.name, ci.HOUSEHOLD.name), ("education", "household"))
        self.assertFalse(ci.EDUCATION.slugs & ci.HOUSEHOLD.slugs)
        self.assertNotEqual(ci.EDUCATION.identity.release, ci.HOUSEHOLD.identity.release)
        self.assertEqual(ci.HOUSEHOLD.category, "household-services")

    def test_infrastructure_templates_are_least_privilege_and_route_household_explicitly(self):
        root = Path(__file__).resolve().parents[2] / "infra/aws"
        bucket = "arn:aws:s3:::lifeinuk-backups-919693204623-eu-west-2/editorial/"
        statements = {s["Sid"]: s for s in json.loads((root / "instance-role-editorial-io.json").read_text())["Statement"]}
        self.assertEqual(statements["ReadImmutableEditorialInputs"]["Action"], "s3:GetObjectVersion")
        self.assertEqual(statements["ReadImmutableEditorialInputs"]["Resource"],
                         [bucket + "education/packages/*", bucket + "household/packages/*"])
        self.assertEqual(statements["StoreAndReadEditorialAuditReceipts"]["Action"], ["s3:GetObject", "s3:PutObject"])
        self.assertEqual(statements["StoreAndReadEditorialAuditReceipts"]["Resource"],
                         [bucket + p for p in ("education/receipts/*", "education/publications/*",
                                               "household/receipts/*", "household/publications/*")])
        self.assertEqual(set(statements), {"ReadImmutableEditorialInputs", "StoreAndReadEditorialAuditReceipts"})
        for action in ("import", "publish"):
            doc = (root / f"ssm-editorial-{action}.yml").read_text()
            self.assertIn("allowedValues: [education, household" + (", manifest]" if action == "publish" else "]"), doc)
            self.assertIn("default: education", doc)
            self.assertIn(f'ci_release.py {action}-household "$SSM_packageSha256"', doc)
            self.assertIn('test "$SSM_release" = education', doc)

    def test_publish_preview_accepts_household_with_the_same_input_checks(self):
        preview = Path(__file__).resolve().parents[1] / "ci/check-editorial-inputs.py"
        day = datetime.now(timezone.utc).date().isoformat()
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            env = dict(os.environ, ENABLED="true", GITHUB_REF="refs/heads/main",
                       GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_STEP_SUMMARY=str(summary))
            command = [sys.executable, str(preview), "publish-household", "a" * 64, "v1"]
            for receipt, verified_on in (("invalid", day), ("b" * 64, "9999-01-01")):
                self.assertNotEqual(subprocess.run([*command, receipt, verified_on], env=env, capture_output=True).returncode, 0)
            self.assertFalse(summary.exists())
            subprocess.run([*command, "b" * 64, day], env=env, check=True)
            text = summary.read_text()
            self.assertIn("Household Services & Insurance publication", text)
            for value in ("a" * 64, "v1", "b" * 64, day):
                self.assertIn(f"`{value}`", text)
