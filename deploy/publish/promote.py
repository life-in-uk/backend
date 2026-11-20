#!/usr/bin/env python3
"""Stage and verify the approved first-production Education release over SSH loopback."""

import argparse
from datetime import datetime, timezone
from getpass import GetPassWarning, getpass
from hashlib import sha256
from http.client import HTTPConnection, HTTPException
from http.cookies import SimpleCookie
import json
from pathlib import Path
from typing import NamedTuple
import re
import sys
from uuid import UUID
import warnings


WAVES = {
    "wave1": (
        "63b3574a70450e8ff857b193899fde784be5ea8b75705feaf5e12dcc8dfce026",
        (
            "joining-school-after-moving-to-uk",
            "uk-school-stages-and-year-groups",
            "in-year-school-admissions-england",
            "joining-school-during-gcses-england",
            "sixth-form-or-fe-college-england",
        ),
    ),
    "wave2": (
        "d80e8aada4e0105d3d2d584c6aa832aad8727396bfd7fbfa517b35a3c54c860c",
        (
            "primary-school-reception-applications-england",
            "secondary-school-year-7-applications-england",
            "choosing-a-school-and-admission-appeals-england",
            "english-language-support-at-school-england",
            "send-school-support-england",
            "school-meals-attendance-and-transport-england",
        ),
    ),
}
LOCALES = ("zh-CN", "zh-TW", "en", "ur", "hi")
POLICY = "education-school-v1"
RELEASE = "education-20261119T2330Z"
VALID_FROM = "2026-11-19T23:30:00Z"
EDITORIAL_PATH = "/api/owner/editorial"
CANONICAL_VERSION = "guide-content-v1"


class BatchIdentity(NamedTuple):
    release: str
    policy: str
    valid_from: str


EDUCATION_IDENTITY = BatchIdentity(RELEASE, POLICY, VALID_FROM)


class Refused(Exception):
    """A release invariant failed."""


def require(condition, message):
    if not condition:
        raise Refused(message)


def utc(value):
    if value is None:
        return None
    parsed = datetime.fromisoformat(value)
    require(parsed.utcoffset() is not None, "A Guide timestamp has no UTC offset.")
    return parsed.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def canonical_form(guide):
    # Mirror GuideContentDigest's field order, UTC precision and string escapes.
    obj = {"canonicalization": CANONICAL_VERSION}
    for field in ("slug", "category", "title", "summary", "content", "status"):
        obj[field] = guide[field]
    obj["publishedAt"] = utc(guide.get("publishedAt"))
    obj["updatedAt"] = utc(guide["updatedAt"])
    obj["sources"] = [
        {**{key: source.get(key) for key in ("key", "organisation", "title", "url")},
         "accessedAt": utc(source["accessedAt"])}
        for source in guide["sources"]
    ]
    evidence = guide.get("evidence")
    obj["evidence"] = None if evidence is None else [
        {"key": item["key"], "statement": item["statement"],
         "supports": [{key: support.get(key) for key in ("sourceKey", "locator", "excerpt", "note")}
                      for support in item["supports"]]}
        for item in evidence
    ]
    escapes = dict(zip("bfnrt", ("0008", "000c", "000a", "000d", "0009")))
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    return re.sub(r"\\\\|\\([bfnrt])|[\ud800-\udfff]",
                  lambda match: "\\u" + escapes[match[1]] if match[1] else
                  ("\\u%04x" % ord(match[0]) if len(match[0]) == 1 else match[0]), raw)


def load_package(root):
    # Verify both approved manifests and all 55 files before opening a session.
    manifests = {}
    for wave, (expected, _) in WAVES.items():
        raw = (root / wave / "MANIFEST.json").read_bytes()
        require(sha256(raw).hexdigest() == expected, f"{wave}: manifest SHA-256 mismatch.")
        manifests[wave] = json.loads(raw)

    package = {}
    for wave, (_, slugs) in WAVES.items():
        manifest = manifests[wave]
        require(manifest["canonicalization"] == CANONICAL_VERSION, f"{wave}: unexpected canonicalization.")
        records = manifest["records"]
        expected = {(slug, locale) for slug in slugs for locale in LOCALES}
        require(len(records) == len(expected) and {(r["slug"], r["locale"]) for r in records} == expected,
                f"{wave}: records do not match the school slug and locale allowlist.")
        paths = {Path("drafts", slug, locale + ".json") for slug, locale in expected}
        require(paths == {path.relative_to(root / wave) for path in (root / wave / "drafts").rglob("*.json")},
                f"{wave}: unexpected or missing draft files.")
        sources = {r["slug"]: r["contentHash"] for r in records if r["locale"] == "zh-CN"}
        for record in records:
            slug, locale = record["slug"], record["locale"]
            label = f"{wave}/{slug}/{locale}"
            path = Path("drafts", slug, locale + ".json")
            require(record["path"] == path.as_posix(), f"{label}: unexpected manifest path.")
            raw = (root / wave / path).read_bytes()
            require(sha256(raw).hexdigest() == record["sha256"], f"{label}: file SHA-256 mismatch.")
            guide = json.loads(raw)
            require(guide["slug"] == slug and guide["category"] == "education" and
                    guide["status"] == "DRAFT" and guide["publishedAt"] is None,
                    f"{label}: expected an allowlisted Education draft.")
            content_hash = sha256(canonical_form(guide).encode()).hexdigest()
            require(content_hash == record["contentHash"] and record["sourceContentHash"] == sources[slug],
                    f"{label}: Guide or Chinese source hash mismatch.")
            package.setdefault(slug, {})[locale] = (guide, content_hash)
    return package


class OwnerSession:
    def __init__(self, port, *, secure_cookie=False):
        self.connection = HTTPConnection("127.0.0.1", port, timeout=20)
        self.origin = f"http://127.0.0.1:{port}"
        self.secure_cookie = secure_cookie
        self.cookie = None
        self.csrf = None

    def request(self, method, path, payload=None, expected=200, *, bearer=None):
        headers = {"Origin": self.origin, "Accept": "application/json"}
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        if self.cookie and bearer is None:
            headers["Cookie"] = self.cookie
        if self.csrf and bearer is None:
            headers["X-CSRF-Token"] = self.csrf
        if payload is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(payload).encode()
        # A direct loopback connection neither follows redirects nor uses proxy settings.
        self.connection.request(method, path, body=payload, headers=headers)
        with self.connection.getresponse() as response:
            raw = response.read()
            require(response.status == expected, f"{method} {path}: HTTP {response.status}, expected {expected}.")
            if method == "POST" and path == EDITORIAL_PATH + "/session":
                cookies = SimpleCookie()
                for name, value in response.getheaders():
                    if name.lower() == "set-cookie":
                        cookies.load(value)
                require("JSESSIONID" in cookies, "Owner API did not issue a session cookie.")
                cookie = cookies["JSESSIONID"]
                require(bool(cookie["secure"]) == self.secure_cookie, "Unexpected owner session cookie security setting.")
                self.cookie = "JSESSIONID=" + cookie.coded_value
            return json.loads(raw) if expected == 200 else None

    def login(self, password, role="DRAFT"):
        view = self.request("POST", EDITORIAL_PATH + "/session", {"role": role, "credential": password})
        self.csrf = view["csrfToken"]
        require(view["role"] == role and self.csrf, "Unexpected owner session role.")

    def close(self):
        try:
            if self.cookie and self.csrf:
                self.request("DELETE", EDITORIAL_PATH + "/session", expected=204)
        finally:
            self.connection.close()


def batch_path(batch_id):
    require(str(UUID(batch_id)) == batch_id, "Owner API returned an invalid batch UUID.")
    return f"{EDITORIAL_PATH}/batches/{batch_id}"


def latest_target_batches(session, package):
    rows = session.request("GET", EDITORIAL_PATH + "/batches")
    require(isinstance(rows, list) and len(rows) < 100, "Owner API batch inventory is incomplete.")
    targets = {}
    for row in rows:
        slug = row["slug"]
        if slug in package:
            require(slug not in targets, f"{slug}: multiple destination batches. Reconcile before continuing.")
            targets[slug] = row
    require(len(rows) + len(package) - len(targets) < 100,
            "There is insufficient room to verify all targets in the owner API inventory.")
    for slug in package:
        session.request("GET", f"/api/guides/{slug}", expected=404)
    return targets


def inspect_batch(session, slug, guides, batch_id, *, reviewed=False, identity=EDUCATION_IDENTITY):
    detail = session.request("GET", batch_path(batch_id))
    status = "READY" if reviewed else "DRAFT"
    return inspect_batch_detail(detail, slug, guides, batch_id, statuses={status}, reviewed=reviewed, identity=identity)


def inspect_batch_detail(detail, slug, guides, batch_id, *, statuses, reviewed, identity=EDUCATION_IDENTITY):
    batch = detail["batch"]
    source_id = batch["sourceRevisionId"]
    require(str(UUID(source_id)) == source_id, f"{slug}: invalid source revision UUID.")
    require(batch["batchId"] == batch_id and batch["slug"] == slug and batch["status"] in statuses and
            batch["sourceContentHash"] == guides["zh-CN"][1] and detail["policyVersion"] == identity.policy and
            detail["sourceVersion"] == identity.release and utc(batch["validFrom"]) == utc(identity.valid_from) and
            batch["validUntil"] is None,
            f"{slug}: unexpected batch identity, release metadata, source hash or state (expected {' or '.join(sorted(statuses))}).")
    locales = {}
    for row in detail["locales"]:
        locale = row["locale"]
        require(locale in guides and locale not in locales, f"{slug}: unexpected or duplicate destination locale.")
        guide, content_hash = guides[locale]
        revision_id = row["localeRevisionId"]
        require(str(UUID(revision_id)) == revision_id and row["batchId"] == batch_id and
                row["sourceRevisionId"] == source_id and row["contentHash"] == content_hash and
                row["validationStatus"] == "VALID" and row["canonicalContent"] == canonical_form(guide),
                f"{slug}/{locale}: destination revision, content or hash mismatch.")
        if reviewed:
            require(row["reviewStatus"] == "PASSED" and row["reviewedHash"] == content_hash and
                    row["reviewer"] == "claude-independent", f"{slug}/{locale}: independent hash-bound review missing.")
        elif reviewed is False:
            require(row["reviewStatus"] == "PENDING" and row["reviewedHash"] is None,
                    f"{slug}/{locale}: destination already has review activity.")
        locales[locale] = {"localeRevisionId": revision_id, "contentHash": content_hash}
    require(set(locales) in ({"zh-CN"}, set(LOCALES)) and (not reviewed or set(locales) == set(LOCALES)),
            f"{slug}: unexpected destination locale set.")
    return {"batchId": batch_id, "sourceRevisionId": source_id,
            "sourceContentHash": batch["sourceContentHash"], "locales": locales}


def import_drafts(session, package, *, identity=EDUCATION_IDENTITY):
    targets = latest_target_batches(session, package)
    # Reconcile every existing target before creating or modifying any Guide.
    staged = {slug: inspect_batch(session, slug, package[slug], row["batchId"], identity=identity)
              for slug, row in targets.items()}
    for slug, guides in package.items():
        if slug not in staged:
            batch = session.request("POST", EDITORIAL_PATH + "/batches", {
                "idempotencyKey": identity.release + ":" + slug, "policyVersion": identity.policy,
                "sourceVersion": identity.release, "validFrom": identity.valid_from, "validUntil": None,
                "source": guides["zh-CN"][0],
            })
            staged[slug] = inspect_batch(session, slug, guides, batch["batchId"], identity=identity)
        receipt = staged[slug]
        if set(receipt["locales"]) == set(LOCALES):
            continue
        path = batch_path(receipt["batchId"])
        template = session.request("GET", path + "/translation-export")["responseTemplate"]
        linkage = {key: receipt[key] for key in ("batchId", "sourceRevisionId", "sourceContentHash")}
        require(template["contractVersion"] == "translation-package-v1" and
                all(template[key] == value for key, value in linkage.items()) and
                sorted(row["locale"] for row in template["translations"]) == sorted(LOCALES[1:]),
                f"{slug}: unexpected destination translation template.")
        for row in template["translations"]:
            require(row["sourceRevisionId"] == linkage["sourceRevisionId"] and
                    row["sourceContentHash"] == linkage["sourceContentHash"],
                    f"{slug}: destination translation references the wrong source.")
            row["guide"] = guides[row["locale"]][0]
        session.request("POST", path + "/translations/import", template)
        staged[slug] = inspect_batch(session, slug, guides, receipt["batchId"], identity=identity)
        require(set(staged[slug]["locales"]) == set(LOCALES), f"{slug}: translations are incomplete.")
    return staged


def verify_drafts(session, package, receipt):
    targets = latest_target_batches(session, package)
    require(set(targets) == set(package), "Destination is missing an allowlisted draft batch.")
    verified = {
        slug: inspect_batch(session, slug, guides, targets[slug]["batchId"], reviewed=True)
        for slug, guides in package.items()
    }
    require(verified == receipt, "Production revision IDs or hashes differ from the draft import receipt.")
    return verified


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check-package", "import-drafts", "verify-drafts"))
    parser.add_argument("package", type=Path)
    parser.add_argument("--port", type=int, default=18080, help="local SSH forward port on 127.0.0.1")
    parser.add_argument("--receipt", type=Path, help="completed import receipt, required for verify-drafts")
    args = parser.parse_args()
    if (args.mode == "verify-drafts") != (args.receipt is not None):
        parser.error("--receipt is required only for verify-drafts.")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535.")
    try:
        package = load_package(args.package)
        if args.mode == "check-package":
            print(f"Verified {len(package)} approved school slugs and {sum(map(len, package.values()))} DRAFT files.")
            return 0
        receipt = json.loads(args.receipt.read_bytes()) if args.receipt else None
        session = OwnerSession(args.port)
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", GetPassWarning)
                session.login(getpass("Owner DRAFT password: ", stream=sys.stderr))
            results = (import_drafts(session, package) if args.mode == "import-drafts"
                       else verify_drafts(session, package, receipt))
        finally:
            try:
                session.close()
            except (Refused, OSError, HTTPException):
                print("Could not close the owner session. Close the SSH tunnel and let the session expire.",
                      file=sys.stderr)
        print(json.dumps(results, indent=2, sort_keys=True))
        return 0
    except Refused as error:
        print(f"Refused: {error}", file=sys.stderr)
    except (GetPassWarning, EOFError):
        print("Refused: a private interactive terminal is required for the password.", file=sys.stderr)
    except (OSError, HTTPException, ValueError, KeyError, TypeError, AttributeError):
        # Never log request/response bodies, cookies, credentials or draft text.
        print("Refused: invalid package, receipt or API response, or loopback connection failed.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
