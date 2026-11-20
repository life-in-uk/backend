"""Content release boundary and recovery tests. No live cloud or database calls."""

from contextlib import nullcontext
from copy import deepcopy
from datetime import date
from io import BytesIO
import json
from pathlib import Path
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
                ci.stage({}, "a" * 64, Path(tmp))
            ci.import_drafts.assert_not_called()

    def test_publish_backup_failure_prevents_review_and_publication_writes(self):
        receipts = {slug: {"batchId": str(UUID(int=index)), "sourceRevisionId": str(UUID(int=index))}
                    for index, slug in enumerate(sorted(ci.SLUGS), 1)}
        session = Mock()
        session.request.return_value = [{"slug": slug, **receipt} for slug, receipt in receipts.items()]
        review = {"verifiedOn": "2026-11-20", "reviews": dict.fromkeys(ci.SLUGS, {})}
        state = {"revisions": {slug: [receipt["sourceRevisionId"]] for slug, receipt in receipts.items()},
                 "publications": {}}
        with tempfile.TemporaryDirectory() as tmp, patch.multiple(
            ci, signed_review=Mock(return_value=(review, receipts)), read_state=Mock(return_value=state),
            owner_session=Mock(return_value=nullcontext(session)), batch_state=Mock(return_value=("DRAFT", [], None)),
            public_inventory=Mock(return_value={}), verify_visibility=Mock(),
            backup=Mock(side_effect=ci.Refused("backup failed")), credential=Mock(),
        ):
            with self.assertRaisesRegex(ci.Refused, "backup failed"):
                ci.publish(dict.fromkeys(ci.SLUGS, {}), "a" * 64, "b" * 64, "v1", Path(tmp))
            self.assertEqual([call.args[0] for call in session.request.call_args_list], ["GET"])
            ci.credential.assert_not_called()

    def test_signed_review_fails_before_backup_on_invalid_signature(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            signed_zip = BytesIO()
            with ZipFile(signed_zip, "w") as writer:
                writer.writestr("review.json", "{}")
                writer.writestr("review.sig", "invalid")
            raw = signed_zip.getvalue()

            def fetch(kind, want, version, destination):
                self.assertEqual(kind, "reviews")
                destination.write_bytes(raw)
                return raw

            with patch.multiple(ci, fetch=Mock(side_effect=fetch),
                                run=Mock(side_effect=ci.Refused("signature failed")), backup=Mock()):
                with self.assertRaisesRegex(ci.Refused, "signature failed"):
                    ci.publish({}, "a" * 64, ci.digest(raw), "v1", root)
                ci.backup.assert_not_called()

    def test_signed_review_rejects_extra_school_and_duplicate_json(self):
        with self.assertRaisesRegex(ci.Refused, "Duplicate JSON field"):
            ci.read_json(b'{"passed":false,"passed":true}')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with ZipFile(root / "input.zip", "w") as writer:
                writer.writestr("review.json", ci.encoded({
                    "release": ci.RELEASE, "packageSha256": "a" * 64, "receiptSha256": "c" * 64,
                    "verifiedOn": date.today().isoformat(), "reviews": {"university-guide": {}},
                }))
                writer.writestr("review.sig", "test")
            raw = (root / "input.zip").read_bytes()

            def fetch(kind, want, version, destination):
                destination.write_bytes(raw)
                return raw

            with patch.multiple(ci, fetch=Mock(side_effect=fetch), run=Mock()):
                with self.assertRaisesRegex(ci.Refused, "school scope"):
                    ci.signed_review(root, "a" * 64, ci.digest(raw), "v1")
                self.assertEqual(ci.fetch.call_count, 1)

    def test_published_resume_requires_exact_open_publication_and_receipt(self):
        slug = sorted(ci.SLUGS)[0]
        identifier = str(UUID(int=1))
        source = str(UUID(int=2))
        publication = str(UUID(int=3))
        receipt = {"sourceRevisionId": source, "batchId": identifier,
                   "locales": {locale: {} for locale in ci.LOCALES}}
        verdicts = {locale: {"passed": True, "findings": []} for locale in ci.LOCALES}
        detail = {"batch": {"status": "PUBLISHED"}, "locales": [
            {"locale": locale, "reviewStatus": "PASSED", "reviewedHash": "hash", "contentHash": "hash",
             "reviewer": ci.REVIEWER, "reviewResult": "[]"} for locale in ci.LOCALES
        ]}
        history = [{"id": publication, "ended": False, "sourceRevisionId": source,
                    "checklistId": "standard-v1", "verifiedOn": "2026-11-20"}]
        session = Mock()
        session.request.return_value = detail
        with patch.object(ci, "inspect_batch_detail", return_value=receipt) as inspect:
            verify = lambda records: ci.batch_state(session, slug, {}, receipt, verdicts, records, "2026-11-20")
            self.assertEqual(verify(history), ("PUBLISHED", [], publication))
            for field, value in (("ended", True), ("sourceRevisionId", publication),
                                 ("checklistId", "family-visa-v1"), ("verifiedOn", "2026-11-19")):
                bad = deepcopy(history)
                bad[0][field] = value
                with self.subTest(field=field), self.assertRaisesRegex(ci.Refused, "conflicting"):
                    verify(bad)
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
