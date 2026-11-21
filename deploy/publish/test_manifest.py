"""Manifest scope, authorization and replay tests. No network or credentials."""

from contextlib import nullcontext
from copy import deepcopy
from hashlib import sha256
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
import manifest

REPO = Path(__file__).resolve().parents[2]
CREDIT_SLUG = "uk-credit-history-and-credit-score"
CREDIT_COMMIT = "87c4def5ae3b1f137276c7b4329cf24f521b582a"
CREDIT_PACKAGE = REPO / "docs/editorial" / CREDIT_SLUG
CREDIT_MANIFEST_DIGEST = "afc2cd7c155536286849ce92f4adf1bc943c88f9515256457275abc3ca6a11c6"
DAY = "2026-11-21"
SYNTHETIC_SLUG = "synthetic-uk-money-guide"


class ManifestReleaseTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        raw = (CREDIT_PACKAGE / "EDITORIAL-MANIFEST.json").read_bytes()
        self.assertEqual(sha256(raw).hexdigest(), CREDIT_MANIFEST_DIGEST, CREDIT_COMMIT)
        self.reviewed = manifest.read_json(raw)
        for row in self.reviewed["variants"]:
            raw = (CREDIT_PACKAGE / row["path"]).read_bytes()
            self.assertEqual(sha256(raw).hexdigest(), row["sha256"], CREDIT_COMMIT)
            self.guide_path(CREDIT_SLUG, row["locale"]).write_bytes(raw)
            doc = manifest.read_json(raw)
            doc["slug"] = SYNTHETIC_SLUG
            self.guide_path(SYNTHETIC_SLUG, row["locale"]).write_bytes(ci.encoded(doc))
        self.archive = self.root / "reviewed.zip"
        self.manifest_hash = manifest.pack(self.root, self.archive, "money", DAY, [CREDIT_SLUG])

    def guide_path(self, slug, locale):
        path = self.root / "guides" / slug / f"{locale}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def rebuild(self, change_manifest=None, change_file=None):
        with ZipFile(self.archive) as original:
            files = {name: original.read(name) for name in original.namelist()}
        approved = manifest.read_json(files[manifest.MANIFEST])
        if change_manifest:
            change_manifest(approved)
        files[manifest.MANIFEST] = ci.encoded(approved)
        if change_file:
            change_file(files)
        output = self.root / "changed.zip"
        with ZipFile(output, "w") as changed:
            for name, raw in files.items():
                changed.writestr(name, raw)
        return output, ci.digest(files[manifest.MANIFEST])

    def test_credit_package_preserves_the_five_reviewed_file_and_canonical_hashes(self):
        approved, package = manifest.load_archive(self.archive, self.manifest_hash)
        self.assertEqual(set(package), {CREDIT_SLUG})
        self.assertEqual(approved["category"], "money")
        for reviewed in self.reviewed["variants"]:
            locale = reviewed["locale"]
            self.assertEqual(package[CREDIT_SLUG][locale][1], reviewed["contentSha256"])
            self.assertEqual(approved["articles"][CREDIT_SLUG][locale]["fileSha256"], reviewed["sha256"])

    def test_multiple_guides_use_the_same_manifest_contract(self):
        archive = self.root / "multiple.zip"
        approved = manifest.pack(self.root, archive, "money", DAY, [CREDIT_SLUG, SYNTHETIC_SLUG])
        _, package = manifest.load_archive(archive, approved)
        self.assertEqual(set(package), {CREDIT_SLUG, SYNTHETIC_SLUG})
        self.assertTrue(all(set(rows) == set(ci.LOCALES) for rows in package.values()))

    def test_forged_unapproved_manifest_fails_even_with_matching_archive_digest(self):
        modified, new_manifest = self.rebuild(lambda data: data.update(verifiedOn="2026-11-20"))
        self.assertNotEqual(new_manifest, self.manifest_hash)
        with patch.multiple(ci, fetch=Mock(side_effect=lambda *args, **kw: (
                args[-1].write_bytes(modified.read_bytes()))), owner_session=Mock(), backup=Mock()):
            with self.assertRaisesRegex(ci.Refused, "approved digest"):
                ci.release_reviewed_archive(ci.digest(modified.read_bytes()), "v1", self.manifest_hash, self.root)
            ci.owner_session.assert_not_called()
            ci.backup.assert_not_called()

    def test_file_hash_and_canonical_hash_are_checked_separately(self):
        name = f"guides/{CREDIT_SLUG}/en.json"
        archive, want = self.rebuild(change_file=lambda data: data.update({name: data[name] + b" "}))
        with self.assertRaisesRegex(ci.Refused, "file hash mismatch"):
            manifest.load_archive(archive, want)
        archive, want = self.rebuild(lambda data: data["articles"][CREDIT_SLUG]["en"].update(contentSha256="f" * 64))
        with self.assertRaisesRegex(ci.Refused, "canonical content hash mismatch"):
            manifest.load_archive(archive, want)

    def test_missing_locales_extra_files_and_wrong_category_fail(self):
        scenarios = [
            (lambda data: data["articles"][CREDIT_SLUG].pop("hi"), None, "five manifest locales"),
            (None, lambda files: files.pop(f"guides/{CREDIT_SLUG}/hi.json"), "exactly the approved"),
            (None, lambda files: files.update({"../other.json": b"{}"}), "exactly the approved"),
            (lambda data: data.update(category="education"), None, "identity or draft status"),
        ]
        for modify_manifest, modify_files, reason in scenarios:
            with self.subTest(reason=reason):
                archive, want = self.rebuild(modify_manifest, modify_files)
                with self.assertRaisesRegex(ci.Refused, reason):
                    manifest.load_archive(archive, want)

    def test_import_refuses_unmanaged_rows_and_duplicate_or_unlinked_batches(self):
        source = str(UUID(int=1))
        target = {"slug": CREDIT_SLUG, "sourceRevisionId": source}
        empty = {"guideRows": [], "revisions": {}, "publications": {}, "batches": []}
        rows = {"guideRows": [CREDIT_SLUG]}
        revisions = {"revisions": {CREDIT_SLUG: [source]}}
        duplicate = {"batches": [target, target], **revisions}
        session = Mock()
        for data, reason in ((rows, "already exists"), (revisions, "revision history"), (duplicate, "duplicate")):
            with self.subTest(reason=reason), self.assertRaisesRegex(ci.Refused, reason):
                ci.prepare_import(session, {CREDIT_SLUG: {}}, {**empty, **data}, exact=True)
        valid = {**empty, **revisions, "batches": [target]}
        self.assertEqual(ci.prepare_import(session, {CREDIT_SLUG: {}}, valid, exact=True), {CREDIT_SLUG: target})
        session.request.assert_not_called()

    def test_resume_uses_first_checkpoint_and_cannot_reimport_reviewed_batches(self):
        archive_hash = ci.digest(self.archive.read_bytes())
        receipt = "d" * 64
        stage = Mock(return_value=receipt)
        checkpoint = Mock(return_value=receipt)
        publication = Mock(return_value="e" * 64)
        state = {"reviewStarted": False, "publications": {}}
        with patch.multiple(ci, fetch=Mock(), read_state=Mock(return_value=state),
                            stage=stage, import_checkpoint=checkpoint, publish=publication):
            # The mocked S3 destination is the real local archive for validation.
            ci.fetch.side_effect = lambda *args, **kw: args[-1].write_bytes(self.archive.read_bytes())
            result = ci.release_reviewed_archive(archive_hash, "v1", self.manifest_hash, self.root)
            self.assertEqual(result, "e" * 64)
            release = stage.call_args.kwargs["release"]
            self.assertEqual(release.slugs, {CREDIT_SLUG})
            self.assertEqual(release.checklist, "standard-v1")
            checkpoint.assert_called_once_with(self.root, archive_hash, "v1", release, receipt)
            state["reviewStarted"] = True
            checkpoint.reset_mock()
            ci.release_reviewed_archive(archive_hash, "v1", self.manifest_hash, self.root)
            stage.assert_called_once()
            checkpoint.assert_called_once_with(self.root, archive_hash, "v1", release)
            self.assertEqual(publication.call_count, 2)

    def test_checkpoint_refuses_swapped_manifest_or_object_version(self):
        h = "a" * 64
        release = ci.Release("household", "money", {CREDIT_SLUG}, ci.EDUCATION_IDENTITY, self.manifest_hash)
        record = {"packageSha256": h, "packageVersion": "v1",
                  "manifestSha256": self.manifest_hash, "receiptSha256": "b" * 64}

        def get(*args, **kwargs):
            self.assertIn(f"editorial/household/receipts/by-package/{h}.json", args)
            Path(args[-1]).write_bytes(ci.encoded(record))

        with patch.object(ci, "run", side_effect=get):
            self.assertEqual(ci.import_checkpoint(self.root, h, "v1", release), record["receiptSha256"])
            for field, value in (("packageVersion", "v2"), ("manifestSha256", "c" * 64)):
                old = record[field]
                record[field] = value
                with self.assertRaisesRegex(ci.Refused, "does not match"):
                    ci.import_checkpoint(self.root, h, "v1", release)
                record[field] = old

    def test_unprotected_dispatch_and_missing_approval_path_are_refused(self):
        summary = self.root / "summary"
        env = dict(os.environ, ENABLED="true", GITHUB_EVENT_NAME="workflow_dispatch",
                   GITHUB_REF="refs/heads/main", GITHUB_STEP_SUMMARY=str(summary))
        command = [sys.executable, str(REPO / "deploy/ci/check-editorial-inputs.py"), "release-manifest", "a" * 64, "v1"]
        for name, value in (("ENABLED", "false"), ("GITHUB_REF", "refs/heads/topic"), ("GITHUB_EVENT_NAME", "push")):
            self.assertNotEqual(subprocess.run(command + [self.manifest_hash], env={**env, name: value},
                                               capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run(command, env=env, capture_output=True).returncode, 0)
        self.assertFalse(summary.exists())
        subprocess.run(command + [self.manifest_hash], env=env, check=True)
        self.assertIn(self.manifest_hash, summary.read_text())
        self.assertIn("authorizes private import and publication", summary.read_text())
        job = (REPO / ".github/workflows/editorial-publish-job.yml").read_text()
        preview, protected = job.split("\n  publish:\n")
        self.assertNotIn("id-token: write", preview)
        self.assertIn("environment: production", protected)
        self.assertIn("needs: prepare", protected)
        self.assertIn("LifeInUK-PublishEducationGuides", protected)
        self.assertNotIn("release-manifest", (REPO / "infra/aws/ssm-editorial-import.yml").read_text())

    def test_same_protected_publish_path_handles_single_guide_and_batches(self):
        for slugs in ((CREDIT_SLUG,), (CREDIT_SLUG, SYNTHETIC_SLUG)):
            with self.subTest(slugs=slugs):
                self.assert_publication_sequence(slugs)

    def assert_publication_sequence(self, slugs):
        identities = {slug: {"slug": slug, "batchId": str(UUID(int=i)),
                             "sourceRevisionId": str(UUID(int=i + 20))}
                      for i, slug in enumerate(slugs, 1)}
        receipts = {slug: {**row, "locales": {loc: {"contentHash": "c" * 64} for loc in ci.LOCALES}}
                    for slug, row in identities.items()}
        release = ci.Release("household", "money", frozenset(slugs), ci.EDUCATION_IDENTITY, self.manifest_hash)
        state = {"batches": list(identities.values()), "publications": {},
                 "revisions": {slug: [row["sourceRevisionId"]] for slug, row in identities.items()}}
        publications = {slug: str(UUID(int=i + 40)) for i, slug in enumerate(slugs, 1)}
        steps = {slug: iter([("DRAFT", list(ci.LOCALES), None), ("READY", [], None),
                             ("APPROVED", [], None), ("PUBLISHED", [], publications[slug])])
                 for slug in slugs}
        session = Mock()

        with patch.multiple(ci, load_import_receipt=Mock(return_value=receipts), read_state=Mock(return_value=state),
                            owner_session=Mock(return_value=nullcontext(session)),
                            batch_state=Mock(side_effect=lambda _, slug, *a, **k: next(steps[slug])),
                            release_inventory=Mock(return_value={"unchanged": True}), verify_visibility=Mock(),
                            backup=Mock(return_value="verified-backup"), credential=Mock(return_value="local-test-token"),
                            save_receipt=Mock(return_value="d" * 64)):
            result = ci.publish(dict.fromkeys(slugs, {}), "a" * 64, "b" * 64, DAY, self.root, release=release)
            self.assertEqual(result, "d" * 64)
            ci.owner_session.assert_called_once_with("APPROVAL")
            ci.backup.assert_called_once_with("pre-household-publish")
            ci.verify_visibility.assert_called_with(ci.edge, receipts, slugs=release.slugs, category="money")
            report = ci.save_receipt.call_args.args[0]
            self.assertEqual(report["publications"], publications)
            self.assertEqual(report["batches"], receipts)
            self.assertEqual(report["manifestSha256"], self.manifest_hash)

        for row in identities.values():
            batch_id = row["batchId"]
            path = ci.batch_path(batch_id)
            calls = [call for call in session.request.call_args_list if batch_id in call.args[1]]
            self.assertEqual([call.args[:2] for call in calls[5:]], [("POST", path + "/approve"), ("POST", path + "/publish")])
            self.assertEqual(len(calls[:5]), 5)
            self.assertTrue(all(call.kwargs["bearer"] == "local-test-token" for call in calls[:5]))
            self.assertIsNone(calls[-1].args[2]["expectCurrentPublicationId"])

    def test_generic_rollback_cannot_replay_a_withdrawn_publication(self):
        locale_rows = {locale: {} for locale in ci.LOCALES}
        receipt = {"batchId": str(UUID(int=9)), "sourceRevisionId": str(UUID(int=10)), "locales": locale_rows}
        findings = [{"review": "test"}]
        detail = {"batch": {"status": "PUBLISHED"}, "locales": [
            {"locale": locale, "reviewStatus": "PASSED", "reviewer": ci.REVIEW_SERVICE_ACTOR,
             "reviewedHash": "digest", "contentHash": "digest", "reviewResult": ci.encoded(findings).decode()}
            for locale in ci.LOCALES
        ]}
        history = [{"id": str(UUID(int=11)), "ended": False, "sourceRevisionId": receipt["sourceRevisionId"],
                    "checklistId": "family-visa-v1", "verifiedOn": DAY}]
        session = Mock()
        session.request.return_value = detail
        with patch.object(ci, "inspect_batch_detail", return_value=receipt):
            go = lambda h: ci.batch_state(session, CREDIT_SLUG, {}, receipt, findings, h, DAY,
                                         checklist="family-visa-v1")
            self.assertEqual(go(history)[-1], history[0]["id"])
            withdrawn = deepcopy(history)
            withdrawn[0]["ended"] = True
            with self.assertRaisesRegex(ci.Refused, "withdrawn"):
                go(withdrawn)
