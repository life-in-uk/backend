"""Content release boundary and recovery tests. No live cloud or database calls."""

from contextlib import nullcontext
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from uuid import UUID
import warnings
from zipfile import ZipFile

import ci_release as ci


class EditorialReleaseChecks(unittest.TestCase):
    def test_archive_accepts_only_exact_regular_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "input.zip"
            with ZipFile(archive, "w") as writer:
                writer.writestr("wave1/MANIFEST.json", "manifest")
            ci.unpack(archive, root / "valid", {"wave1/MANIFEST.json"})
            self.assertEqual((root / "valid/wave1/MANIFEST.json").read_text(), "manifest")
            for bad in ("../unrelated", "/absolute", "university.json"):
                with ZipFile(archive, "w") as writer:
                    writer.writestr(bad, "unexpected")
                with self.subTest(bad=bad), self.assertRaises(ci.Refused):
                    ci.unpack(archive, root / "invalid", {"wave1/MANIFEST.json"})
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with ZipFile(archive, "w") as writer:
                    writer.writestr("wave1/MANIFEST.json", "first")
                    writer.writestr("wave1/MANIFEST.json", "second")
            with self.assertRaises(ci.Refused):
                ci.unpack(archive, root / "duplicate", {"wave1/MANIFEST.json"})
        self.assertEqual(len(ci.package_names()), 57)

    def test_s3_fetch_checks_version_digest_and_scope(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "download"
            destination.write_bytes(b"immutable")
            digest = ci.digest(b"immutable")
            with patch.object(ci, "run", return_value=b'{"VersionId":"v1"}') as run:
                self.assertEqual(ci.fetch("packages", digest, "v1", destination), b"immutable")
                self.assertIn(f"editorial/education/packages/{digest}.zip", run.call_args.args)
                with self.assertRaisesRegex(ci.Refused, "version does not match"):
                    ci.fetch("packages", digest, "v2", destination)
                with self.assertRaisesRegex(ci.Refused, "SHA-256 does not match"):
                    ci.fetch("packages", "f" * 64, "v1", destination)
                with self.assertRaisesRegex(ci.Refused, "immutable S3 version"):
                    ci.fetch("packages", digest, "null", destination)

    def test_backup_failure_prevents_draft_writes(self):
        with tempfile.TemporaryDirectory() as tmp, patch.multiple(
            ci, public_inventory=Mock(return_value={}), owner_session=Mock(return_value=nullcontext(Mock())),
            read_state=Mock(return_value={}), prepare_import=Mock(), verify_visibility=Mock(),
            backup=Mock(side_effect=ci.Refused("backup failed")), import_drafts=Mock(),
        ):
            with self.assertRaisesRegex(ci.Refused, "backup failed"):
                ci.stage({slug: dict.fromkeys(ci.LOCALES) for slug in ci.SLUGS}, "a" * 64, Path(tmp))
            ci.import_drafts.assert_not_called()

    def test_publish_backup_failure_prevents_review_and_publication_writes(self):
        receipts = {slug: {"batchId": str(UUID(int=index)), "sourceRevisionId": str(UUID(int=index))}
                    for index, slug in enumerate(sorted(ci.SLUGS), 1)}
        session = Mock()
        session.request.return_value = [{"slug": slug, **receipt} for slug, receipt in receipts.items()]
        state = {"revisions": {slug: [receipt["sourceRevisionId"]] for slug, receipt in receipts.items()},
                 "publications": {}}
        with tempfile.TemporaryDirectory() as tmp, patch.multiple(
            ci, load_import_receipt=Mock(return_value=receipts), read_state=Mock(return_value=state),
            owner_session=Mock(return_value=nullcontext(session)), batch_state=Mock(return_value=("DRAFT", [], None)),
            public_inventory=Mock(return_value={}), verify_visibility=Mock(),
            backup=Mock(side_effect=ci.Refused("backup failed")), credential=Mock(),
        ):
            with self.assertRaisesRegex(ci.Refused, "backup failed"):
                ci.publish(dict.fromkeys(ci.SLUGS, {}), "a" * 64, "b" * 64, "2025-01-01", Path(tmp))
            self.assertEqual([call.args[0] for call in session.request.call_args_list], ["GET"])
            ci.credential.assert_not_called()

    def test_receipt_digest_mismatch_prevents_backup_and_editorial_session(self):
        with tempfile.TemporaryDirectory() as tmp, patch.multiple(ci, backup=Mock(), owner_session=Mock()):
            root = Path(tmp)

            def download(*args, purpose):
                self.assertIn(f"editorial/education/receipts/{'b' * 64}.json", args)
                Path(args[-1]).write_bytes(b"replaced receipt")
                return b"{}"

            with patch.object(ci, "run", side_effect=download):
                with self.assertRaisesRegex(ci.Refused, "SHA-256 does not match"):
                    ci.publish({}, "a" * 64, "b" * 64, "2025-01-01", root)
            ci.owner_session.assert_not_called()
            ci.backup.assert_not_called()

    def test_receipt_must_match_package_and_school_scope(self):
        batches = dict.fromkeys(ci.SLUGS, {})
        receipt = {"release": ci.RELEASE, "packageSha256": "a" * 64, "backup": "backup", "batches": batches}
        changed = ({**receipt, "batches": {**batches, "university-guide": {}}},
                   {**receipt, "batches": {}}, {**receipt, "packageSha256": "c" * 64})
        with tempfile.TemporaryDirectory() as tmp, patch.object(ci, "fetch") as fetch:
            root = Path(tmp)
            fetch.return_value = ci.encoded(receipt)
            self.assertEqual(ci.load_import_receipt(root, "a" * 64, "b" * 64), batches)
            for wrong in changed:
                with self.subTest(wrong=wrong), self.assertRaisesRegex(ci.Refused, "scope or package digest"):
                    fetch.return_value = ci.encoded(wrong)
                    ci.load_import_receipt(root, "a" * 64, "b" * 64)
            fetch.return_value = b'{"batches":{},"batches":{}}'
            with self.assertRaisesRegex(ci.Refused, "Duplicate JSON field"):
                ci.load_import_receipt(root, "a" * 64, "b" * 64)

    def test_invalid_review_dates_block_publication_before_receipt_fetch(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(ci, "load_import_receipt") as load:
            for invalid in ("20250101", "2025-02-30", "9999-01-01"):
                with self.subTest(invalid=invalid), self.assertRaisesRegex(ci.Refused, "Invalid local review date"):
                    ci.publish({}, "a" * 64, "b" * 64, invalid, Path(tmp))
            load.assert_not_called()

    def test_approval_preview_displays_exact_inputs_and_rejects_invalid_inputs(self):
        preview = Path(__file__).resolve().parents[1] / "ci/check-editorial-inputs.py"
        day = datetime.now(timezone.utc).date().isoformat()
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            env = dict(os.environ, ENABLED="true", GITHUB_REF="refs/heads/main",
                       GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_STEP_SUMMARY=str(summary))
            command = [sys.executable, str(preview), "publish", "a" * 64, "v1"]
            for receipt, verified_on in (("invalid", day), ("b" * 64, "20250202"), ("b" * 64, "9999-01-01")):
                result = subprocess.run([*command, receipt, verified_on], env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(summary.exists())
            subprocess.run([*command, "b" * 64, day], env=env, check=True)
            for value in ("a" * 64, "v1", "b" * 64, day):
                self.assertIn(f"`{value}`", summary.read_text())

    def test_published_resume_requires_exact_open_publication_and_receipt(self):
        slug = sorted(ci.SLUGS)[0]
        identifier = str(UUID(int=1))
        source = str(UUID(int=2))
        publication = str(UUID(int=3))
        receipt = {"sourceRevisionId": source, "batchId": identifier,
                   "locales": {locale: {} for locale in ci.LOCALES}}
        findings = [{"importReceiptSha256": "b" * 64, "verifiedOn": "2025-01-01"}]
        detail = {"batch": {"status": "PUBLISHED"}, "locales": [
            {"locale": locale, "reviewStatus": "PASSED", "reviewedHash": "hash", "contentHash": "hash",
             "reviewer": ci.REVIEW_SERVICE_ACTOR, "reviewResult": json.dumps(findings)} for locale in ci.LOCALES
        ]}
        history = [{"id": publication, "ended": False, "sourceRevisionId": source,
                    "checklistId": "standard-v1", "verifiedOn": "2026-11-20"}]
        session = Mock()
        session.request.return_value = detail
        with patch.object(ci, "inspect_batch_detail", return_value=receipt) as inspect:
            verify = lambda records: ci.batch_state(session, slug, {}, receipt, findings, records, "2026-11-20")
            self.assertEqual(verify(history), ("PUBLISHED", [], publication))
            for field, value in (("ended", True), ("sourceRevisionId", publication),
                                 ("checklistId", "family-visa-v1"), ("verifiedOn", "2026-11-19")):
                bad = deepcopy(history)
                bad[0][field] = value
                with self.subTest(field=field), self.assertRaisesRegex(ci.Refused, "conflicting"):
                    verify(bad)
            detail["locales"][0]["reviewResult"] = "[]"
            with self.assertRaisesRegex(ci.Refused, "does not match the owner-approved import receipt"):
                verify(history)
            detail["locales"][0]["reviewResult"] = json.dumps(findings)
            inspect.return_value = {**receipt, "sourceRevisionId": publication}
            with self.assertRaisesRegex(ci.Refused, "changed since import"):
                verify(history)

    def test_oidc_roles_have_distinct_trusted_jobs_and_ssm_documents(self):
        repo = Path(__file__).resolve().parents[2]
        root = repo / "infra/aws"
        trust = lambda file: json.loads((root / file).read_text())["Statement"][0]["Condition"]["StringEquals"]
        refs = set()
        for name, file, title in (
            ("backend-deploy", "deploy-production-job", "Deploy production"),
            ("editorial-import", "editorial-import-job", "Import private drafts"),
            ("editorial-publish", "editorial-publish-job", "Publish reviewed guides"),
        ):
            conditions = trust(name + "-trust.json")
            self.assertEqual(conditions["token.actions.githubusercontent.com:ref"], "refs/heads/main")
            self.assertTrue(conditions["token.actions.githubusercontent.com:sub"].endswith(":environment:production"))
            self.assertEqual(conditions["token.actions.githubusercontent.com:workflow"], title)
            ref = conditions["token.actions.githubusercontent.com:job_workflow_ref"]
            self.assertEqual(ref, f"life-in-uk/backend/.github/workflows/{file}.yml@refs/heads/main")
            refs.add(ref)
        self.assertEqual(len(refs), 3)
        for action, document in (("import", "LifeInUK-ImportEducationDrafts"), ("publish", "LifeInUK-PublishEducationGuides")):
            policy = json.loads((root / f"editorial-{action}-policy.json").read_text())["Statement"]
            self.assertEqual([statement["Action"] for statement in policy], ["ssm:SendCommand", "ssm:GetCommandInvocation"])
            self.assertEqual([arn.rsplit("/", 1)[-1] for arn in policy[0]["Resource"]],
                             ["i-0d0c006308c9ffba0", document])
            doc = (root / f"ssm-editorial-{action}.yml").read_text()
            self.assertIn(f"allowedValues: [{action}]", doc)
            self.assertIn(f"ci_release.py {action} ", doc)
            self.assertIn('interpolationType: ENV_VAR', doc)
