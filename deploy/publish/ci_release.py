#!/usr/bin/env python3
"""Host-only Education release runner. SSM supplies object identities, never secrets."""

import argparse
from base64 import b64encode
from contextlib import contextmanager
from datetime import date, datetime, timezone
from hashlib import sha256
from http.client import HTTPConnection, HTTPSConnection
import json
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time
from uuid import UUID
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

from promote import (
    EDITORIAL_PATH, LOCALES, RELEASE, WAVES, OwnerSession, Refused, batch_path,
    import_drafts, inspect_batch_detail, latest_target_batches, load_package, require,
)

HOST = Path("/opt/lifeinuk")
CONF = Path("/etc/lifeinuk/editorial-ci")
BUCKET = "lifeinuk-backups-919693204623-eu-west-2"
SLUGS = {slug for _, slugs in WAVES.values() for slug in slugs}
HEX = r"[0-9a-f]{64}"
VERSION = r"[A-Za-z0-9._~+/=-]{1,1024}"
MAX_BYTES = 8 * 1024 * 1024
REVIEW_NAMESPACE = "lifeinuk-education-publish"
REVIEWER = "claude-independent"


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def digest(value):
    return sha256(value).hexdigest()


def uuid(value):
    require(str(UUID(value)) == value, "Invalid receipt UUID.")
    return value


def run(*args, input=None, purpose):
    result = subprocess.run(args, input=input, capture_output=True, cwd=HOST, check=False)
    require(result.returncode == 0, f"{purpose} failed.")
    return result.stdout


def checked_bytes(path, want=None):
    require(path.stat().st_size <= MAX_BYTES, "Release object exceeds size limit.")
    raw = path.read_bytes()
    require(want is None or digest(raw) == want, "Release object SHA-256 does not match.")
    return raw


def unpack(path, destination, names):
    # Never use extractall: an archive is data, and only these exact regular files are accepted.
    with ZipFile(path) as archive:
        members = archive.infolist()
        require(len(members) == len(names) and {m.filename for m in members} == names,
                "Unexpected, missing or duplicate archive member.")
        require(sum(m.file_size for m in members) <= MAX_BYTES, "Expanded archive exceeds size limit.")
        for member in members:
            mode = stat.S_IFMT(member.external_attr >> 16)
            require(not member.is_dir() and mode in (0, stat.S_IFREG), "Archive contains a non-regular file.")
            target = destination / member.filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(member))


def read_json(raw):
    def unique(pairs):
        result = dict(pairs)
        require(len(result) == len(pairs), "Duplicate JSON field in release evidence.")
        return result
    return json.loads(raw, object_pairs_hook=unique)


def package_names():
    return {f"{wave}/MANIFEST.json" for wave in WAVES} | {
        f"{wave}/drafts/{slug}/{locale}.json"
        for wave, (_, slugs) in WAVES.items() for slug in slugs for locale in LOCALES
    }


def fetch(kind, want, version, destination):
    require(re.fullmatch(HEX, want) is not None, "Invalid object digest.")
    require(version is None or re.fullmatch(VERSION, version) is not None and version != "null",
            "An immutable S3 version is required.")
    suffix = "zip" if kind in {"packages", "reviews"} else "json"
    key = f"editorial/education/{kind}/{want}.{suffix}"
    args = ["aws", "s3api", "get-object", "--bucket", BUCKET, "--key", key]
    if version is not None:
        args += ["--version-id", version]
    result = json.loads(run(*args, str(destination), purpose="Private S3 fetch"))
    if version is not None:
        require(result.get("VersionId") == version, "S3 object version does not match.")
    return checked_bytes(destination, want)


def save_receipt(receipt, directory, kind):
    raw = encoded(receipt)
    want = digest(raw)
    path = directory / "receipt.json"
    path.write_bytes(raw)
    key = f"editorial/education/{kind}/{want}.json"
    # Single-write object. A lost S3 response is recoverable by comparing already stored bytes.
    checksum = b64encode(bytes.fromhex(want)).decode()
    result = subprocess.run(
        ["aws", "s3api", "put-object", "--bucket", BUCKET, "--key", key, "--body", str(path),
         "--if-none-match", "*", "--server-side-encryption", "AES256",
         "--checksum-algorithm", "SHA256", "--checksum-sha256", checksum],
        capture_output=True, cwd=HOST, check=False,
    )
    if result.returncode:
        require(fetch(kind, want, None, directory / "existing.json") == raw, "Stored receipt mismatch.")
    else:
        require(json.loads(result.stdout).get("ChecksumSHA256") == checksum, "S3 receipt checksum mismatch.")
    return want


def credential(name):
    path = CONF / name
    properties = path.stat(follow_symlinks=False)
    require(stat.S_ISREG(properties.st_mode) and properties.st_uid == 0 and properties.st_mode & 0o077 == 0,
            "Editorial credential file must be root-owned and private.")
    value = path.read_text().strip()
    require(re.fullmatch(r"[!-~]{32,512}", value) is not None, "Editorial credential format is invalid.")
    return value


@contextmanager
def owner_session(role):
    session = OwnerSession(8080, secure_cookie=True)
    try:
        session.login(credential(role.lower()), role)
        yield session
    finally:
        session.close()


def edge(path, status):
    time.sleep(0.15)  # Stay below the public per-viewer limit.
    connection = HTTPSConnection("lifeinuk.info", timeout=15)
    try:
        connection.request("GET", path)
        with connection.getresponse() as response:
            require(response.status == status, f"Public edge returned unexpected status for {path}.")
            raw = response.read(MAX_BYTES + 1)
            require(len(raw) <= MAX_BYTES, "Public response exceeds size limit.")
            return raw
    finally:
        connection.close()


def public_inventory(expected):
    for path in ("/api/owner/editorial/session", "/api/editorial/guide-batches/visibility-check"):
        edge(path, 404)
    snapshot = {}
    for locale in LOCALES:
        path = f"/api/guides/locales/{locale}"
        rows = json.loads(edge(path, 200))
        require(all(row["category"] != "university-applications-student-visas" for row in rows),
                "University is present in a public listing.")
        education = {row["slug"] for row in rows if row["category"] == "education" or row["slug"] in SLUGS}
        require(education == expected, "Public Education listing has unexpected scope.")
        snapshot[locale] = sorted((row for row in rows if row["slug"] not in SLUGS), key=lambda row: row["slug"])
    xml = ElementTree.fromstring(edge("/sitemap.xml", 200))
    paths = {loc.text for loc in xml.iter("{http://www.sitemaps.org/schemas/sitemap/0.9}loc")}
    require(all("/university-applications-student-visas" not in path for path in paths),
            "University is present in the sitemap.")
    education = {path for path in paths if "/education" in path}
    expected_paths = ({f"https://lifeinuk.info/{locale}/education" for locale in LOCALES}
                      if expected else set())
    expected_paths.update(f"https://lifeinuk.info/{locale}/education/{slug}" for slug in expected for locale in LOCALES)
    require(education == expected_paths, "Public Education sitemap has unexpected scope.")
    snapshot["sitemap"] = sorted(paths - education)
    return snapshot


def verify_visibility(reader, expected):
    for slug in sorted(SLUGS):
        for locale in LOCALES:
            path = f"/api/guides/{slug}/locales/{locale}"
            if slug not in expected:
                reader(path, 404)
                continue
            row = json.loads(reader(path, 200))
            receipt = expected[slug]
            require(row["locale"] == locale and row["guide"]["slug"] == slug and row["guide"]["category"] == "education"
                    and row["sourceRevisionId"] == receipt["sourceRevisionId"]
                    and row["contentHash"] == receipt["locales"][locale]["contentHash"],
                    "Public variant differs from the release receipt.")


def local_reader(path, status):
    # Use the same direct HTTP connection as the editorial client, with no cookie or bearer.
    conn = HTTPConnection("127.0.0.1", 8080, timeout=20)
    try:
        conn.request("GET", path)
        with conn.getresponse() as response:
            require(response.status == status, f"Loopback returned unexpected status for {path}.")
            return response.read(MAX_BYTES + 1)
    finally:
        conn.close()


def read_state():
    # Read-only audit. All Guide writes and transitions use the editorial API.
    slugs = ", ".join("'" + slug + "'" for slug in sorted(SLUGS))
    sql = f"""
      BEGIN READ ONLY;
      SELECT jsonb_build_object(
        'active', EXISTS (SELECT 1 FROM guide_workflow_activation),
        'revisions', COALESCE((SELECT jsonb_object_agg(slug, revision_ids) FROM (
          SELECT slug, jsonb_agg(id ORDER BY id) AS revision_ids FROM guide_revision
          WHERE slug IN ({slugs}) GROUP BY slug
        ) r), '{{}}'::jsonb),
        'publications', COALESCE((SELECT jsonb_object_agg(slug, releases) FROM (
          SELECT slug, jsonb_agg(jsonb_build_object('id', id, 'sourceRevisionId', revision_id,
            'ended', ended_at IS NOT NULL, 'checklistId', checklist_id, 'verifiedOn', verified_on) ORDER BY id) AS releases FROM guide_publication
          WHERE slug IN ({slugs}) GROUP BY slug
        ) p), '{{}}'::jsonb)
      );
      COMMIT;
    """
    state = json.loads(run("docker", "compose", "exec", "-T", "db", "psql", "-XqAt",
                           "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "life_in_uk",
                           input=sql.encode(), purpose="Read-only publication audit"))
    require(state["active"], "Guide publication workflow is inactive.")
    return state


def backup(label):
    raw = run("env", "BACKUP_S3_REQUIRED=1", str(HOST / "scripts/backup.sh"), label, purpose="Verified off-instance backup")
    name = Path(raw.decode().strip()).name
    require(re.fullmatch(r"life_in_uk-[0-9]{8}T[0-9]{6}Z-" + label + r"\.dump", name) is not None,
            "Backup did not return a verified reference.")
    return name


def prepare_import(session, package, state):
    require(not state["publications"], "A target already has publication history.")
    targets = latest_target_batches(session, package)
    require(state["revisions"] == {s: [uuid(row["sourceRevisionId"])] for s, row in targets.items()},
            "Target revision history does not match the resumable draft batches.")


def stage(package, archive_hash, directory):
    before = public_inventory(set())
    with owner_session("DRAFT") as session:
        prepare_import(session, package, read_state())
        verify_visibility(local_reader, {})
        backup_name = backup("pre-education-import")
        batches = import_drafts(session, package)
        prepare_import(session, package, read_state())
        verified = {slug: inspect_batch_detail(session.request("GET", batch_path(row["batchId"])),
                    slug, package[slug], row["batchId"], statuses={"DRAFT"}, reviewed=False)
                    for slug, row in batches.items()}
        require(verified == batches and set(verified) == SLUGS
                and all(set(row["locales"]) == set(LOCALES) for row in verified.values()),
                "Final draft receipt does not contain exactly 55 verified variants.")
    verify_visibility(edge, {})
    require(public_inventory(set()) == before, "Public inventory changed during private import.")
    return save_receipt({"release": RELEASE, "packageSha256": archive_hash, "backup": backup_name,
                         "batches": batches}, directory, "receipts")


def signed_review(directory, archive_hash, review_hash, review_version):
    fetch("reviews", review_hash, review_version, directory / "review.zip")
    unpack(directory / "review.zip", directory, {"review.json", "review.sig"})
    raw = checked_bytes(directory / "review.json")
    run("ssh-keygen", "-Y", "verify", "-f", str(CONF / "allowed_signers"), "-I", REVIEWER,
        "-n", REVIEW_NAMESPACE, "-s", str(directory / "review.sig"),
        input=raw, purpose="Independent review signature verification")
    review = read_json(raw)
    require(set(review) == {"release", "packageSha256", "receiptSha256", "verifiedOn", "reviews"}
            and review["release"] == RELEASE and review["packageSha256"] == archive_hash
            and set(review["reviews"]) == SLUGS, "Signed review release or school scope is incorrect.")
    day = date.fromisoformat(review["verifiedOn"])
    require(day.isoformat() == review["verifiedOn"] and day <= datetime.now(timezone.utc).date(),
            "Independent verification date is invalid.")
    for verdicts in review["reviews"].values():
        require(set(verdicts) == set(LOCALES), "Signed review has incomplete locales.")
        for verdict in verdicts.values():
            require(set(verdict) == {"passed", "findings"} and verdict["passed"] is True
                    and isinstance(verdict["findings"], list) and len(verdict["findings"]) <= 100
                    and len(json.dumps(verdict["findings"], ensure_ascii=False)) <= 64000,
                    "Every variant needs a passing signed verdict within API limits.")
    receipt = read_json(fetch("receipts", review["receiptSha256"], None, directory / "import.json"))
    require(receipt["release"] == RELEASE and receipt["packageSha256"] == archive_hash
            and set(receipt["batches"]) == SLUGS, "Import receipt scope or package digest is incorrect.")
    return review, receipt["batches"]


def batch_state(session, slug, guides, receipt, verdicts, history, verified_on):
    detail = session.request("GET", batch_path(receipt["batchId"]))
    status = detail["batch"]["status"]
    actual = inspect_batch_detail(detail, slug, guides, receipt["batchId"],
                                  statuses={"DRAFT", "READY", "APPROVED", "PUBLISHED"}, reviewed=None)
    require(actual == receipt and set(actual["locales"]) == set(LOCALES), "Batch IDs or hashes changed since import.")
    pending = []
    for row in detail["locales"]:
        verdict = verdicts[row["locale"]]
        if status == "DRAFT" and row["reviewStatus"] == "PENDING" and row["reviewedHash"] is None:
            pending.append(row["locale"])
        else:
            require(row["reviewStatus"] == "PASSED" and row["reviewedHash"] == row["contentHash"]
                    and row["reviewer"] == REVIEWER and row["reviewResult"] is not None
                    and json.loads(row["reviewResult"]) == verdict["findings"],
                    "Existing review does not match the signed independent verdict.")
    if status == "PUBLISHED":
        require(len(history) == 1 and history[0]["ended"] is False
                and history[0]["sourceRevisionId"] == receipt["sourceRevisionId"]
                and history[0]["checklistId"] == "standard-v1" and history[0]["verifiedOn"] == verified_on,
                "Published batch has a conflicting or withdrawn publication.")
        return status, pending, uuid(history[0]["id"])
    require(not history, "Unpublished batch has unexpected publication history.")
    return status, pending, None


def publish(package, archive_hash, review_hash, review_version, directory):
    review, receipts = signed_review(directory, archive_hash, review_hash, review_version)
    state = read_state()
    require(state["revisions"] == {s: [uuid(r["sourceRevisionId"])] for s, r in receipts.items()},
            "Destination revisions differ from the signed receipt.")
    with owner_session("APPROVAL") as session:
        rows = session.request("GET", EDITORIAL_PATH + "/batches")
        require(isinstance(rows, list) and len(rows) < 100, "Owner API batch inventory is incomplete.")
        inventory = [r for r in rows if r["slug"] in SLUGS]
        require(len(inventory) == len(SLUGS)
                and {r["slug"]: r["batchId"] for r in inventory} == {s: r["batchId"] for s, r in receipts.items()},
                "The batch inventory differs from the signed receipt.")

        def inspect(slug, snapshot):
            return batch_state(session, slug, package[slug], receipts[slug],
                               review["reviews"][slug], snapshot["publications"].get(slug, []), review["verifiedOn"])

        ready = {slug: inspect(slug, state) for slug in sorted(SLUGS)}
        published = {slug: row[2] for slug, row in ready.items() if row[0] == "PUBLISHED"}
        before = public_inventory(set(published))
        verify_visibility(local_reader, {s: receipts[s] for s in published})
        backup_name = backup("pre-education-publish")
        review_token = credential("review")
        for slug, (status, pending, _) in ready.items():
            if status == "PUBLISHED":
                continue
            receipt = receipts[slug]
            path = batch_path(receipt["batchId"])
            for locale in pending:
                verdict = review["reviews"][slug][locale]
                session.request("POST", f"/api/editorial/guide-batches/{receipt['batchId']}/reviews/{locale}",
                                {**verdict, "contentHash": receipt["locales"][locale]["contentHash"]}, bearer=review_token)
            status, _, _ = inspect(slug, read_state())
            if status == "READY":
                session.request("POST", path + "/approve")
                status, _, _ = inspect(slug, read_state())
            require(status == "APPROVED", "Batch is missing current approval.")
            session.request("POST", path + "/publish", {"checklistId": "standard-v1",
                            "verifiedOn": review["verifiedOn"], "expectCurrentPublicationId": None})
            status, _, publication_id = inspect(slug, read_state())
            require(status == "PUBLISHED", "Publication was not recorded.")
            published[slug] = publication_id
            print(f"Published {slug} {publication_id}", flush=True)
    verify_visibility(edge, receipts)
    require(public_inventory(SLUGS) == before, "Unrelated public inventory changed during publication.")
    return save_receipt({"release": RELEASE, "packageSha256": archive_hash,
                         "reviewSha256": review_hash, "backup": backup_name,
                         "publications": published}, directory, "publications")


def host_ready():
    app = run("docker", "compose", "ps", "-q", "app", purpose="Find backend container").decode().strip()
    revision = run("docker", "inspect", "-f", '{{index .Config.Labels "org.opencontainers.image.revision"}}',
                   app, purpose="Read backend revision").decode().strip()
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None, "Backend revision is unavailable.")
    require(run("docker", "port", app, "8080/tcp", purpose="Check backend binding").strip() == b"127.0.0.1:8080",
            "Backend port is not loopback-only.")
    require(json.loads(local_reader("/actuator/health", 200))["status"] == "UP", "Backend is not healthy.")
    checker = Path(__file__).with_name("check-production-editorial.py")
    result = json.loads(run("/usr/bin/python3", "-E", "-s", "-B", str(checker), purpose="Editorial configuration check"))
    require(result and all(value is True for value in result.values()), "Editorial configuration check failed.")
    print(f"Backend revision {revision}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("import", "publish"))
    parser.add_argument("package_sha256")
    parser.add_argument("package_version")
    parser.add_argument("review_sha256", nargs="?")
    parser.add_argument("review_version", nargs="?")
    args = parser.parse_args()
    try:
        require((args.review_sha256 is not None and args.review_version is not None)
                if args.action == "publish" else args.review_sha256 is None and args.review_version is None,
                "Use review inputs only for publication.")
        host_ready()
        with tempfile.TemporaryDirectory(prefix="lifeinuk-editorial-") as scratch:
            directory = Path(scratch)
            fetch("packages", args.package_sha256, args.package_version, directory / "package.zip")
            unpack(directory / "package.zip", directory, package_names())
            package = load_package(directory)
            if args.action == "import":
                receipt = stage(package, args.package_sha256, directory)
            else:
                receipt = publish(package, args.package_sha256, args.review_sha256, args.review_version, directory)
        print(f"RESULT {args.action} receipt_sha256={receipt}")
    except Refused as failure:
        parser.exit(1, f"Editorial release refused: {failure}\n")
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, AttributeError,
            RuntimeError, ElementTree.ParseError):
        # Exceptions can contain remote response bodies or local sensitive file data.
        parser.exit(1, "Editorial release failed. Reconcile with the private receipt before retrying.\n")


if __name__ == "__main__":
    main()
