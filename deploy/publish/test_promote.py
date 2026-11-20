import copy
from getpass import GetPassWarning
from io import StringIO
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from unittest.mock import patch
from uuid import uuid4

import promote


SLUG = "joining-school-after-moving-to-uk"
OTHER = "uk-school-stages-and-year-groups"
FIXTURE = Path(__file__).resolve().parents[2] / "src/test/resources/guides/guide-content-v1-shared.json"


def fixture_guide(slug=SLUG, locale="zh-CN"):
    guide = json.loads(FIXTURE.read_bytes())["guide"]
    guide.update(slug=slug, title=locale, category="education", status="DRAFT", publishedAt=None)
    return guide


def encoded(value):
    return json.dumps(value).encode()


def digest(guide):
    return sha256(promote.canonical_form(guide).encode()).hexdigest()


class PackageChecks(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def create_wave(self, wave, slug):
        records = []
        for locale in promote.LOCALES:
            relative = Path("drafts", slug, locale + ".json")
            path = self.root / wave / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            guide = fixture_guide(slug, locale)
            raw = encoded(guide)
            path.write_bytes(raw)
            records.append({"slug": slug, "locale": locale, "path": relative.as_posix(),
                            "sha256": sha256(raw).hexdigest(), "contentHash": digest(guide),
                            "sourceContentHash": digest(fixture_guide(slug))})
        return {"canonicalization": "guide-content-v1", "records": records}

    def save_manifest(self, wave, manifest):
        raw = encoded(manifest)
        (self.root / wave / "MANIFEST.json").write_bytes(raw)
        return sha256(raw).hexdigest()

    def load(self, change_wave2=lambda manifest: None):
        wave1 = self.create_wave("wave1", SLUG)
        wave2 = self.create_wave("wave2", OTHER)
        change_wave2(wave2)
        pins = {"wave1": (self.save_manifest("wave1", wave1), (SLUG,)),
                "wave2": (self.save_manifest("wave2", wave2), (OTHER,))}
        with patch.object(promote, "WAVES", pins):
            return promote.load_package(self.root)

    def test_canonicalization_matches_backend_golden_fixture(self):
        fixture = json.loads(FIXTURE.read_bytes())
        self.assertEqual(promote.canonical_form(fixture["guide"]), fixture["canonicalForm"])
        self.assertEqual(digest(fixture["guide"]), fixture["sha256"])

    def test_checks_both_manifests_and_all_locales(self):
        result = self.load()
        self.assertEqual(set(result), {SLUG, OTHER})
        self.assertTrue(all(set(guides) == set(promote.LOCALES) for guides in result.values()))

    def test_changed_manifest_rejected_before_network(self):
        wave1 = self.create_wave("wave1", SLUG)
        self.save_manifest("wave1", wave1)
        with patch.object(promote, "OwnerSession") as session, \
             patch("sys.argv", ["promote.py", "import-drafts", str(self.root)]), \
             patch("sys.stderr"):
            self.assertEqual(promote.main(), 1)
        session.assert_not_called()

    def test_second_manifest_is_checked_before_opening_a_session(self):
        first = self.create_wave("wave1", SLUG)
        pins = {"wave1": (self.save_manifest("wave1", first), (SLUG,)),
                "wave2": ("0" * 64, (OTHER,))}
        self.save_manifest("wave2", self.create_wave("wave2", OTHER))
        with patch.object(promote, "WAVES", pins):
            with self.assertRaisesRegex(promote.Refused, "wave2: manifest SHA-256 mismatch"):
                promote.load_package(self.root)

    def test_tampered_file_rejected(self):
        def tamper(manifest):
            path = self.root / "wave2" / manifest["records"][-1]["path"]
            path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(promote.Refused, "file SHA-256 mismatch"):
            self.load(tamper)

    def test_tampered_content_hash_rejected(self):
        with self.assertRaisesRegex(promote.Refused, "Guide or Chinese source hash mismatch"):
            self.load(lambda manifest: manifest["records"][-1].update(contentHash="0" * 64))

    def test_university_slug_or_category_rejected_even_in_a_matching_manifest(self):
        with self.assertRaisesRegex(promote.Refused, "slug and locale allowlist"):
            self.load(lambda manifest: manifest["records"][-1].update(slug="uk-student-visa-application"))

        def tamper(manifest):
            row = manifest["records"][-1]
            path = self.root / "wave2" / row["path"]
            guide = json.loads(path.read_bytes())
            guide["category"] = "university-applications-student-visas"
            raw = encoded(guide)
            path.write_bytes(raw)
            row.update(sha256=sha256(raw).hexdigest(), contentHash=digest(guide))
        with self.assertRaisesRegex(promote.Refused, "expected an allowlisted Education draft"):
            self.load(tamper)


class FakeOwner:
    """Models only the production API's draft endpoints and receipts."""
    def __init__(self):
        self.calls = []
        self.details = {}
        self.requests = {}

    def request(self, method, path, payload=None, expected=200):
        self.calls.append((method, path))
        if path.startswith("/api/guides/"):
            if method != "GET" or expected != 404:
                raise AssertionError((method, path, expected))
            return None
        relative = path.removeprefix(promote.EDITORIAL_PATH + "/batches").strip("/")
        if method == "GET" and not relative:
            return [copy.deepcopy(detail["batch"]) for detail in self.details.values()]
        if method == "POST" and not relative:
            key = payload["idempotencyKey"]
            if key not in self.requests:
                source = payload["source"]
                batch_id, source_id = str(uuid4()), str(uuid4())
                batch = {"slug": source["slug"], "batchId": batch_id, "sourceRevisionId": source_id,
                         "sourceContentHash": digest(source), "status": "DRAFT",
                         "validFrom": payload["validFrom"], "validUntil": payload["validUntil"]}
                self.details[batch_id] = {"batch": batch, "locales": [self.row(batch, "zh-CN", source)],
                                          "policyVersion": payload["policyVersion"],
                                          "sourceVersion": payload["sourceVersion"]}
                self.requests[key] = batch_id
            return copy.deepcopy(self.details[self.requests[key]]["batch"])
        batch_id, _, suffix = relative.partition("/")
        detail = self.details[batch_id]
        batch = detail["batch"]
        if method == "GET" and not suffix:
            return copy.deepcopy(detail)
        linkage = {key: batch[key] for key in ("sourceRevisionId", "sourceContentHash")}
        if method == "GET" and suffix == "translation-export":
            return {"responseTemplate": {"contractVersion": "translation-package-v1", "batchId": batch_id,
                    **linkage, "translations": [{"locale": locale, **linkage, "guide": {}}
                                                for locale in promote.LOCALES[1:]]}}
        if method == "POST" and suffix == "translations/import":
            for row in payload["translations"]:
                if any(row[key] != value for key, value in linkage.items()):
                    raise AssertionError("Translation linkage mismatch")
                detail["locales"].append(self.row(batch, row["locale"], row["guide"]))
            return []
        raise AssertionError((method, path))

    @staticmethod
    def row(batch, locale, guide):
        return {"locale": locale, "batchId": batch["batchId"], "sourceRevisionId": batch["sourceRevisionId"],
                "localeRevisionId": str(uuid4()), "validationStatus": "VALID", "contentHash": digest(guide),
                "canonicalContent": promote.canonical_form(guide), "reviewStatus": "PENDING",
                "reviewedHash": None, "reviewer": None}


class PromotionChecks(unittest.TestCase):
    def setUp(self):
        self.owner = FakeOwner()
        self.package = {SLUG: {}}
        for locale in promote.LOCALES:
            guide = fixture_guide(locale=locale)
            self.package[SLUG][locale] = (guide, digest(guide))

    def test_import_is_draft_only_and_retries_without_replacing_ids(self):
        receipt = promote.import_drafts(self.owner, self.package)
        self.assertEqual(len(receipt[SLUG]["locales"]), 5)
        self.assertEqual(set(self.owner.details), {receipt[SLUG]["batchId"]})
        writes = [path for method, path in self.owner.calls if method == "POST"]
        self.assertEqual(writes, [promote.EDITORIAL_PATH + "/batches",
                                  promote.batch_path(receipt[SLUG]["batchId"]) + "/translations/import"])
        self.owner.calls.clear()
        self.assertEqual(promote.import_drafts(self.owner, self.package), receipt)
        self.assertTrue(all(method == "GET" for method, _ in self.owner.calls))

    def test_resumes_a_source_only_draft(self):
        self.owner.request("POST", promote.EDITORIAL_PATH + "/batches", {
            "idempotencyKey": promote.RELEASE + ":" + SLUG, "policyVersion": promote.POLICY,
            "sourceVersion": promote.RELEASE, "validFrom": promote.VALID_FROM, "validUntil": None,
            "source": self.package[SLUG]["zh-CN"][0],
        })
        batch_id, = self.owner.details
        receipt = promote.import_drafts(self.owner, self.package)[SLUG]
        self.assertEqual(receipt["batchId"], batch_id)
        self.assertEqual(set(receipt["locales"]), set(promote.LOCALES))

    def test_stops_before_writes_if_destination_draft_does_not_match(self):
        receipt = promote.import_drafts(self.owner, self.package)
        detail = self.owner.details[receipt[SLUG]["batchId"]]
        detail["locales"][0]["contentHash"] = "0" * 64
        self.owner.calls.clear()
        with self.assertRaisesRegex(promote.Refused, "destination revision, content or hash mismatch"):
            promote.import_drafts(self.owner, self.package)
        self.assertTrue(all(method == "GET" for method, _ in self.owner.calls))

    def test_refuses_a_truncated_batch_inventory(self):
        inventory = [{"slug": f"unrelated-{index}"} for index in range(100)]
        with patch.object(self.owner, "request", return_value=inventory) as request:
            with self.assertRaisesRegex(promote.Refused, "inventory is incomplete"):
                promote.import_drafts(self.owner, self.package)
        request.assert_called_once_with("GET", promote.EDITORIAL_PATH + "/batches")

    def test_refuses_multiple_batches_for_a_target(self):
        with patch.object(self.owner, "request", return_value=[{"slug": SLUG}, {"slug": SLUG}]) as request:
            with self.assertRaisesRegex(promote.Refused, "multiple destination batches"):
                promote.import_drafts(self.owner, self.package)
        request.assert_called_once_with("GET", promote.EDITORIAL_PATH + "/batches")

    def test_refuses_import_after_any_review_activity(self):
        receipt = promote.import_drafts(self.owner, self.package)
        batch = self.owner.details[receipt[SLUG]["batchId"]]
        first_locale = batch["locales"][0]
        first_locale.update(reviewStatus="PASSED", reviewedHash=first_locale["contentHash"])
        self.owner.calls.clear()
        with self.assertRaisesRegex(promote.Refused, "already has review activity"):
            promote.import_drafts(self.owner, self.package)
        self.assertTrue(all(method == "GET" for method, _ in self.owner.calls))

    def test_refuses_existing_public_guide(self):
        request = self.owner.request

        def public_collision(method, path, payload=None, expected=200):
            if path == f"/api/guides/{SLUG}":
                raise promote.Refused(f"{method} {path}: HTTP 200, expected {expected}.")
            return request(method, path, payload, expected)

        with patch.object(self.owner, "request", side_effect=public_collision) as spy:
            with self.assertRaisesRegex(promote.Refused, "HTTP 200, expected 404"):
                promote.import_drafts(self.owner, self.package)
        self.assertTrue(all(call.args[0] == "GET" for call in spy.call_args_list))

    def test_second_verification_compares_fresh_ids_and_requires_independent_review(self):
        receipt = promote.import_drafts(self.owner, self.package)
        detail = self.owner.details[receipt[SLUG]["batchId"]]
        with self.assertRaisesRegex(promote.Refused, "expected READY"):
            promote.verify_drafts(self.owner, self.package, receipt)
        detail["batch"]["status"] = "READY"
        for row in detail["locales"]:
            row.update(reviewStatus="PASSED", reviewedHash=row["contentHash"], reviewer="claude-independent")
        self.owner.calls.clear()
        self.assertEqual(promote.verify_drafts(self.owner, self.package, receipt), receipt)
        self.assertTrue(all(method == "GET" for method, _ in self.owner.calls))
        detail["locales"][0]["localeRevisionId"] = str(uuid4())
        with self.assertRaisesRegex(promote.Refused, "revision IDs or hashes differ"):
            promote.verify_drafts(self.owner, self.package, receipt)
        detail["locales"][0]["reviewer"] = "mock-adapter"
        with self.assertRaisesRegex(promote.Refused, "independent hash-bound review missing"):
            promote.verify_drafts(self.owner, self.package, receipt)


class CommandSafetyChecks(unittest.TestCase):
    def test_password_input_requires_a_private_terminal(self):
        with patch.object(promote, "load_package", return_value={}), \
             patch.object(promote, "OwnerSession") as session, \
             patch.object(promote, "getpass", side_effect=GetPassWarning), \
             patch("sys.argv", ["promote.py", "import-drafts", "unused"]), \
             patch("sys.stderr", new_callable=StringIO) as errors:
            self.assertEqual(promote.main(), 1)
        session.return_value.login.assert_not_called()
        session.return_value.close.assert_called_once()
        self.assertIn("private interactive terminal is required", errors.getvalue())

    def test_connection_errors_do_not_log_credentials(self):
        secret_marker = "synthetic-password-should-never-appear"
        with patch.object(promote, "load_package", return_value={}), \
             patch.object(promote, "OwnerSession") as session, \
             patch.object(promote, "getpass", return_value=secret_marker), \
             patch("sys.argv", ["promote.py", "import-drafts", "unused"]), \
             patch("sys.stderr", new_callable=StringIO) as errors, \
             patch("sys.stdout", new_callable=StringIO) as output:
            session.return_value.login.side_effect = OSError(secret_marker)
            self.assertEqual(promote.main(), 1)
        self.assertNotIn(secret_marker, errors.getvalue())
        self.assertNotIn(secret_marker, output.getvalue())


class LoopbackSessionChecks(unittest.TestCase):
    def test_session_uses_loopback_origin_cookie_and_csrf_and_refuses_redirects(self):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                calls.append((self.command, self.path, self.headers["Origin"],
                              json.loads(self.rfile.read(int(self.headers["Content-Length"])))["role"]))
                self.send_response(200)
                self.send_header("Set-Cookie", "JSESSIONID=synthetic-session; Path=/; HttpOnly")
                self.end_headers()
                self.wfile.write(encoded({"role": "DRAFT", "csrfToken": "synthetic-csrf"}))

            def do_GET(self):
                calls.append((self.command, self.path))
                self.send_response(302)
                self.send_header("Location", "/must-not-follow")
                self.end_headers()
                self.wfile.write(b"synthetic-secret-must-not-appear")

            def do_DELETE(self):
                calls.append((self.command, self.path, self.headers["Cookie"], self.headers["X-CSRF-Token"]))
                self.send_response(204)
                self.end_headers()

        with HTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            session = promote.OwnerSession(server.server_port)
            path = promote.EDITORIAL_PATH + "/session"
            origin = f"http://127.0.0.1:{server.server_port}"
            try:
                session.login("synthetic-password")
                with self.assertRaisesRegex(promote.Refused, "HTTP 302") as failure:
                    session.request("GET", promote.EDITORIAL_PATH + "/batches")
                self.assertNotIn("synthetic-secret-must-not-appear", str(failure.exception))
            finally:
                session.close()
                server.shutdown()
                thread.join()
        self.assertEqual(calls, [("POST", path, origin, "DRAFT"),
                                 ("GET", promote.EDITORIAL_PATH + "/batches"),
                                 ("DELETE", path, "JSESSIONID=synthetic-session", "synthetic-csrf")])


if __name__ == "__main__":
    unittest.main()
