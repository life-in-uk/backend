#!/usr/bin/env python3
"""Host-only editorial release runner. SSM supplies object identities, never secrets."""

import argparse
from base64 import b64encode
from contextlib import contextmanager
from datetime import date, datetime, timezone
from hashlib import sha256
from http.client import HTTPConnection, HTTPSConnection
import json
from pathlib import Path
import re
from typing import NamedTuple
import stat
import subprocess
import tempfile
import time
from uuid import UUID
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

import household
import manifest
from manifest import read_json
from promote import (
    EDITORIAL_PATH, EDUCATION_IDENTITY, LOCALES, RELEASE, WAVES, BatchIdentity, OwnerSession, Refused, batch_path,
    canonical_form, import_drafts, inspect_batch_detail, latest_target_batches, load_package, require,
)

HOST = Path("/opt/lifeinuk")
CONF = Path("/etc/lifeinuk/editorial-ci")
BUCKET = "lifeinuk-backups-919693204623-eu-west-2"
SLUGS = {slug for _, slugs in WAVES.values() for slug in slugs}
HEX = r"[0-9a-f]{64}"
VERSION = r"[A-Za-z0-9._~+/=-]{1,1024}"
MAX_BYTES = 8 * 1024 * 1024
# The installed backend assigns this legacy actor to its host-only review token.
REVIEW_SERVICE_ACTOR = "claude-independent"


class Release(NamedTuple):
    """One editorial release family: its S3 prefix (name), Guide category, slug allowlist and batch identity."""
    name: str
    category: str
    slugs: frozenset
    identity: BatchIdentity
    manifest_hash: str | None = None
    checklist: str = "standard-v1"


EDUCATION = Release("education", "education", frozenset(SLUGS), EDUCATION_IDENTITY)
HOUSEHOLD = Release("household", household.CATEGORY, household.SLUGS, household.IDENTITY)


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


def package_names():
    return {f"{wave}/MANIFEST.json" for wave in WAVES} | {
        f"{wave}/drafts/{slug}/{locale}.json"
        for wave, (_, slugs) in WAVES.items() for slug in slugs for locale in LOCALES
    }


def fetch(kind, want, version, destination, *, prefix="education"):
    require(re.fullmatch(HEX, want) is not None, "Invalid object digest.")
    require(version is None or re.fullmatch(VERSION, version) is not None and version != "null",
            "An immutable S3 version is required.")
    suffix = "zip" if kind == "packages" else "json"
    key = f"editorial/{prefix}/{kind}/{want}.{suffix}"
    args = ["aws", "s3api", "get-object", "--bucket", BUCKET, "--key", key]
    if version is not None:
        args += ["--version-id", version]
    result = json.loads(run(*args, str(destination), purpose="Private S3 fetch"))
    if version is not None:
        require(result.get("VersionId") == version, "S3 object version does not match.")
    return checked_bytes(destination, want)


def write_audit_object(raw, key, directory):
    want = digest(raw)
    path = directory / "receipt.json"
    path.write_bytes(raw)
    # Single-write object. A lost S3 response is recoverable by comparing already stored bytes.
    checksum = b64encode(bytes.fromhex(want)).decode()
    result = subprocess.run(
        ["aws", "s3api", "put-object", "--bucket", BUCKET, "--key", key, "--body", str(path),
         "--if-none-match", "*", "--server-side-encryption", "AES256",
         "--checksum-algorithm", "SHA256", "--checksum-sha256", checksum],
        capture_output=True, cwd=HOST, check=False,
    )
    if result.returncode:
        return False
    require(json.loads(result.stdout).get("ChecksumSHA256") == checksum, "S3 receipt checksum mismatch.")
    return True


def save_receipt(receipt, directory, kind, *, prefix="education"):
    raw = encoded(receipt)
    want = digest(raw)
    key = f"editorial/{prefix}/{kind}/{want}.json"
    if not write_audit_object(raw, key, directory):
        require(fetch(kind, want, None, directory / "existing.json", prefix=prefix) == raw, "Stored receipt mismatch.")
    return want


def import_checkpoint(directory, archive_hash, version, release, receipt_hash=None):
    # The fixed host can recover the first verified receipt even after a lost
    # publication response. An existing checkpoint is always read and checked.
    identity = {"packageSha256": archive_hash, "packageVersion": version,
                "manifestSha256": release.manifest_hash}
    key = f"editorial/{release.name}/receipts/by-package/{archive_hash}.json"
    if receipt_hash is not None and write_audit_object(
            encoded({**identity, "receiptSha256": receipt_hash}), key, directory):
        return receipt_hash
    path = directory / "checkpoint.json"
    run("aws", "s3api", "get-object", "--bucket", BUCKET, "--key", key, str(path),
        purpose="Read private import checkpoint")
    checkpoint = read_json(checked_bytes(path))
    value = checkpoint.pop("receiptSha256")
    require(identity == checkpoint and re.fullmatch(HEX, value) is not None,
            "Import checkpoint does not match the approved release.")
    return value


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


def public_inventory(expected, *, category="education", slugs=SLUGS):
    for path in ("/api/owner/editorial/session", "/api/editorial/guide-batches/visibility-check"):
        edge(path, 404)
    snapshot = {}
    for locale in LOCALES:
        path = f"/api/guides/locales/{locale}"
        rows = json.loads(edge(path, 200))
        require(all(row["category"] != "university-applications-student-visas" for row in rows),
                "University is present in a public listing.")
        target = {row["slug"] for row in rows if row["category"] == category or row["slug"] in slugs}
        require(target == expected, f"Public {category} listing has unexpected scope.")
        snapshot[locale] = sorted((row for row in rows if row["slug"] not in slugs), key=lambda row: row["slug"])
    xml = ElementTree.fromstring(local_reader("/sitemap.xml", 200))
    paths = {loc.text for loc in xml.iter("{http://www.sitemaps.org/schemas/sitemap/0.9}loc")}
    require(all("/university-applications-student-visas" not in path for path in paths),
            "University is present in the sitemap.")
    target_paths = {path for path in paths if f"/{category}" in path}
    expected_paths = ({f"https://lifeinuk.info/{locale}/{category}" for locale in LOCALES}
                      if expected else set())
    expected_paths.update(f"https://lifeinuk.info/{locale}/{category}/{slug}" for slug in expected for locale in LOCALES)
    require(target_paths == expected_paths, f"Public {category} sitemap has unexpected scope.")
    snapshot["sitemap"] = sorted(paths - target_paths)
    return snapshot


def manifest_inventory(expected, release):
    """Check public API listings and the backend's loopback sitemap projection."""
    for path in ("/api/owner/editorial/session", "/api/editorial/guide-batches/visibility-check"):
        edge(path, 404)
    domain = manifest.DOMAINS[release.category]
    other_hubs, target_hubs, target_paths = set(), set(), set()
    snapshot = {}
    for locale in LOCALES:
        rows = read_json(edge(f"/api/guides/locales/{locale}", 200))
        require(len(rows) == len({row["slug"] for row in rows}),
                f"Public catalogue has duplicate Guides for {locale}.")
        targets = [row for row in rows if row["slug"] in release.slugs]
        require(len(targets) == len(expected) and {row["slug"] for row in targets} == expected
                and all(row["category"] == release.category for row in targets),
                "Public listing differs from the approved manifest.")
        other = [row for row in rows if row["slug"] not in release.slugs]
        snapshot[locale] = sorted(other, key=lambda row: row["slug"])
        hub = f"https://lifeinuk.info/{locale}/{domain}"
        if any(row["category"] == release.category for row in other):
            other_hubs.add(hub)
        elif targets:
            target_hubs.add(hub)
        target_paths.update(f"{hub}/{slug}" for slug in release.slugs)

    # The edge sitemap describes deployed frontend pages and only advances with a frontend release.
    xml = ElementTree.fromstring(local_reader("/sitemap.xml", 200))
    namespace = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
    urls = xml.findall(namespace + "url")
    entries = {row.findtext(namespace + "loc"): row for row in urls}
    require(xml.tag == namespace + "urlset" and len(entries) == len(urls) and None not in entries,
            "Backend sitemap has invalid or duplicate entries.")
    paths = set(entries)
    wanted = {f"https://lifeinuk.info/{locale}/{domain}/{slug}" for locale in LOCALES for slug in expected}
    require(paths & target_paths == wanted and (other_hubs | target_hubs) <= paths,
            "Backend sitemap differs from the approved manifest.")
    for slug in expected:
        alternates = {("en-GB" if locale == "en" else locale, f"https://lifeinuk.info/{locale}/{domain}/{slug}")
                      for locale in LOCALES}
        alternates.add(("x-default", f"https://lifeinuk.info/zh-CN/{domain}/{slug}"))
        for _, href in alternates:
            links = entries[href].findall("{http://www.w3.org/1999/xhtml}link")
            require(len(links) == len(alternates) and {(a.get("hreflang"), a.get("href")) for a in links} == alternates
                    and all(a.get("rel") == "alternate" for a in links),
                    "Guide sitemap alternates differ from the approved locales.")
    topics = set()
    if release.category == "family-visa" and "partner-visa-dependent-children" in release.slugs:
        topics = {f"https://lifeinuk.info/{locale}/family-visa/topics/children-from-previous-relationship"
                  for locale in LOCALES}
        shown = topics if "partner-visa-dependent-children" in expected else set()
        require(paths & topics == shown, "Derived topic sitemap visibility changed unexpectedly.")
    snapshot["sitemap"] = sorted(paths - wanted - target_hubs - topics)
    return snapshot


def release_inventory(expected, release):
    if release.manifest_hash:
        return manifest_inventory(expected, release)
    return public_inventory(expected, category=release.category, slugs=release.slugs)


def verify_visibility(reader, expected, *, slugs=SLUGS, category="education", package=None):
    for slug in sorted(slugs):
        for locale in LOCALES:
            path = f"/api/guides/{slug}/locales/{locale}"
            if slug not in expected:
                reader(path, 404)
                continue
            row = json.loads(reader(path, 200))
            receipt = expected[slug]
            require(row["locale"] == locale and row["guide"]["slug"] == slug and row["guide"]["category"] == category
                    and row["sourceRevisionId"] == receipt["sourceRevisionId"]
                    and row["contentHash"] == receipt["locales"][locale]["contentHash"],
                    "Public variant differs from the release receipt.")
            if package is not None:
                draft, content_hash = package[slug][locale]
                # The API projects the publication timestamps and omits draft status.
                body = {**row["guide"], "status": "DRAFT", "publishedAt": None, "updatedAt": draft["updatedAt"]}
                if draft.get("evidence") is None and body.get("evidence") == []:
                    body["evidence"] = None
                require(digest(canonical_form(body).encode()) == content_hash,
                        f"Public Guide body differs from the approved manifest for {slug}/{locale}.")


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


def read_state(*, slugs=SLUGS):
    # Read-only audit. All Guide writes and transitions use the editorial API.
    require(slugs and all(len(slug) <= 160 and re.fullmatch(manifest.GUIDE_KEY, slug) for slug in slugs),
            "Invalid audit slug scope.")
    slugs = ", ".join("'" + slug + "'" for slug in sorted(slugs))
    sql = f"""
      BEGIN READ ONLY;
      SELECT jsonb_build_object(
        'active', EXISTS (SELECT 1 FROM guide_workflow_activation),
        'guideRows', COALESCE((SELECT jsonb_agg(slug ORDER BY slug) FROM guide WHERE slug IN ({slugs})), '[]'::jsonb),
        'batches', COALESCE((SELECT jsonb_agg(jsonb_build_object('slug', slug, 'batchId', id,
          'sourceRevisionId', source_revision_id) ORDER BY id) FROM guide_content_batch WHERE slug IN ({slugs})), '[]'::jsonb),
        'reviewStarted', EXISTS (SELECT 1 FROM guide_locale_revision r JOIN guide_content_batch b ON b.id = r.batch_id
          WHERE b.slug IN ({slugs}) AND (b.status <> 'DRAFT' OR r.review_status <> 'PENDING')),
        'revisions', COALESCE((SELECT jsonb_object_agg(slug, revision_ids) FROM (
          SELECT slug, jsonb_agg(id ORDER BY id) AS revision_ids FROM guide_revision
          WHERE slug IN ({slugs}) GROUP BY slug
        ) r), '{{}}'::jsonb),
        'currentLocales', COALESCE((SELECT jsonb_object_agg(slug, locales) FROM (
          SELECT c.slug, jsonb_object_agg(c.locale, jsonb_build_object('batchId', c.batch_id,
            'localeRevisionId', r.id, 'contentHash', r.content_hash)) AS locales
          FROM guide_locale_current c JOIN guide_locale_revision r ON r.id = c.locale_revision_id
          WHERE c.slug IN ({slugs}) GROUP BY c.slug
        ) c), '{{}}'::jsonb),
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


def prepare_import(session, package, state, *, exact=False):
    require(not state["publications"], "A target already has publication history.")
    if exact:
        require(not state["guideRows"], "A target already exists in the Guide inventory.")
        rows = state["batches"]
        targets = {row["slug"]: row for row in rows}
        require(len(targets) == len(rows), "A target has duplicate destination batches.")
    else:
        targets = latest_target_batches(session, package)
    require(state["revisions"] == {s: [uuid(row["sourceRevisionId"])] for s, row in targets.items()},
            "Target revision history does not match the resumable draft batches.")
    return targets


def stage(package, archive_hash, directory, *, release=EDUCATION):
    category, prefix, slugs, identity = release.category, release.name, release.slugs, release.identity
    require(set(package) == slugs and all(set(guides) == set(LOCALES) for guides in package.values()),
            "Draft package does not match the release allowlist.")
    before = release_inventory(set(), release)
    with owner_session("DRAFT") as session:
        scope = {"exact": True} if release.manifest_hash else {}
        targets = prepare_import(session, package, read_state(slugs=slugs), **scope)
        verify_visibility(local_reader, {}, slugs=slugs, category=category)
        backup_name = backup(f"pre-{prefix}-import")
        options = {"targets": targets} if release.manifest_hash else {}
        batches = import_drafts(session, package, identity=identity, **options)
        prepare_import(session, package, read_state(slugs=slugs), **scope)
        verified = {slug: inspect_batch_detail(session.request("GET", batch_path(row["batchId"])),
                    slug, package[slug], row["batchId"], statuses={"DRAFT"}, reviewed=False, identity=identity)
                    for slug, row in batches.items()}
        require(verified == batches and set(verified) == slugs
                and all(set(row["locales"]) == set(LOCALES) for row in verified.values()),
                f"Final draft receipt does not contain exactly {len(slugs) * len(LOCALES)} verified variants.")
    verify_visibility(edge, {}, slugs=slugs, category=category)
    require(release_inventory(set(), release) == before,
            "Public inventory changed during private import.")
    return save_receipt({"release": identity.release, "packageSha256": archive_hash, "backup": backup_name,
                         "batches": batches}, directory, "receipts", prefix=prefix)


def load_import_receipt(directory, archive_hash, receipt_hash, release=EDUCATION):
    receipt = read_json(fetch("receipts", receipt_hash, None, directory / "import.json", prefix=release.name))
    require(set(receipt) == {"release", "packageSha256", "backup", "batches"}
            and receipt["release"] == release.identity.release and receipt["packageSha256"] == archive_hash
            and set(receipt["batches"]) == release.slugs, "Import receipt scope or package digest is incorrect.")
    return receipt["batches"]


def batch_state(session, slug, guides, receipt, findings, history, verified_on, identity=EDUCATION_IDENTITY,
                checklist="standard-v1"):
    detail = session.request("GET", batch_path(receipt["batchId"]))
    status = detail["batch"]["status"]
    actual = inspect_batch_detail(detail, slug, guides, receipt["batchId"],
                                  statuses={"DRAFT", "READY", "APPROVED", "PUBLISHED"}, reviewed=None, identity=identity)
    require(actual == receipt and set(actual["locales"]) == set(LOCALES), "Batch IDs or hashes changed since import.")
    pending = []
    for row in detail["locales"]:
        if status == "DRAFT" and row["reviewStatus"] == "PENDING" and row["reviewedHash"] is None:
            pending.append(row["locale"])
        else:
            require(row["reviewStatus"] == "PASSED" and row["reviewedHash"] == row["contentHash"]
                    and row["reviewer"] == REVIEW_SERVICE_ACTOR and row["reviewResult"] is not None
                    and json.loads(row["reviewResult"]) == findings,
                    "Existing review does not match the owner-approved import receipt and verification date.")
    if status == "PUBLISHED":
        require(len(history) == 1 and history[0]["ended"] is False
                and history[0]["sourceRevisionId"] == receipt["sourceRevisionId"]
                and history[0]["checklistId"] == checklist and history[0]["verifiedOn"] == verified_on,
                "Published batch has a conflicting or withdrawn publication.")
        return status, pending, uuid(history[0]["id"])
    require(not history, "Unpublished batch has unexpected publication history.")
    return status, pending, None


def verify_committed_state(state, receipts, publications):
    require(set(state["guideRows"]) == set(publications), "Committed Guide rows differ from the publication scope.")
    require(state["revisions"] == {s: [r["sourceRevisionId"]] for s, r in receipts.items()},
            "Committed revision history differs from the import receipt.")
    require(set(state["publications"]) == set(publications)
            and all(len(state["publications"][s]) == 1
                    and state["publications"][s][0]["id"] == pub
                    and state["publications"][s][0]["sourceRevisionId"] == receipts[s]["sourceRevisionId"]
                    and state["publications"][s][0]["ended"] is False for s, pub in publications.items()),
            "Committed publication history differs from the verified publications.")
    pointers = {s: {loc: {**variant, "batchId": receipts[s]["batchId"]}
                    for loc, variant in receipts[s]["locales"].items()} for s in publications}
    require(state["currentLocales"] == pointers, "Committed locale pointers differ from the import receipt.")


def publish(package, archive_hash, receipt_hash, verified_on, directory, *, release=EDUCATION):
    try:
        day = date.fromisoformat(verified_on)
    except ValueError:
        raise Refused("Invalid local review date.") from None
    require(day.isoformat() == verified_on and day <= datetime.now(timezone.utc).date(),
            "Invalid local review date.")
    slugs, category = release.slugs, release.category
    receipts = load_import_receipt(directory, archive_hash, receipt_hash, release)
    findings = [{
        "source": "Local content review and GitHub Production owner approval",
        "importReceiptSha256": receipt_hash,
        "verifiedOn": verified_on,
    }]
    state = read_state(slugs=slugs)
    require(state["revisions"] == {s: [uuid(r["sourceRevisionId"])] for s, r in receipts.items()},
            "Destination revisions differ from the import receipt.")
    with owner_session("APPROVAL") as session:
        if release.manifest_hash:
            inventory = state["batches"]
        else:
            rows = session.request("GET", EDITORIAL_PATH + "/batches")
            require(isinstance(rows, list) and len(rows) < 100, "Owner API batch inventory is incomplete.")
            inventory = [r for r in rows if r["slug"] in slugs]
        require(len(inventory) == len(slugs)
                and {r["slug"]: r["batchId"] for r in inventory} == {s: r["batchId"] for s, r in receipts.items()},
                "The batch inventory differs from the import receipt.")

        def inspect(slug, snapshot):
            return batch_state(session, slug, package[slug], receipts[slug],
                               findings, snapshot["publications"].get(slug, []), verified_on, release.identity,
                               **({"checklist": release.checklist} if release.manifest_hash else {}))

        ready = {slug: inspect(slug, state) for slug in sorted(slugs)}
        published = {slug: row[2] for slug, row in ready.items() if row[0] == "PUBLISHED"}
        visibility = {"package": package} if release.manifest_hash else {}
        if release.manifest_hash:
            verify_committed_state(state, receipts, published)
        before = release_inventory(set(published), release)
        verify_visibility(local_reader, {s: receipts[s] for s in published}, slugs=slugs, category=category, **visibility)
        backup_name = backup(f"pre-{release.name}-publish")
        review_token = credential("review")
        for slug, (status, pending, _) in ready.items():
            if status == "PUBLISHED":
                continue
            receipt = receipts[slug]
            path = batch_path(receipt["batchId"])
            for locale in pending:
                session.request("POST", f"/api/editorial/guide-batches/{receipt['batchId']}/reviews/{locale}",
                                {"passed": True, "findings": findings,
                                 "contentHash": receipt["locales"][locale]["contentHash"]}, bearer=review_token)
            status, _, _ = inspect(slug, read_state(slugs=slugs))
            if status == "READY":
                session.request("POST", path + "/approve")
                status, _, _ = inspect(slug, read_state(slugs=slugs))
            require(status == "APPROVED", "Batch is missing current approval.")
            session.request("POST", path + "/publish", {"checklistId": release.checklist,
                            "verifiedOn": verified_on, "expectCurrentPublicationId": None})
            status, _, publication_id = inspect(slug, read_state(slugs=slugs))
            require(status == "PUBLISHED", "Publication was not recorded.")
            published[slug] = publication_id
            print(f"Published {slug} {publication_id}", flush=True)
    if release.manifest_hash:
        verify_committed_state(read_state(slugs=slugs), receipts, published)
    verify_visibility(edge, receipts, slugs=slugs, category=category, **visibility)
    after = release_inventory(set(slugs), release)
    require(after == before, "Unrelated inventory changed during publication.")
    report = {"release": release.identity.release, "packageSha256": archive_hash,
              "importReceiptSha256": receipt_hash, "verifiedOn": verified_on, "backup": backup_name,
              "publications": published}
    if release.manifest_hash:
        report.update(manifestSha256=release.manifest_hash, category=category, batches=receipts,
                      catalogueCounts={locale: len(after[locale]) + len(slugs) for locale in LOCALES})
    return save_receipt(report, directory, "publications", prefix=release.name)


def release_reviewed_archive(archive_hash, version, manifest_hash, directory):
    # Keep the existing private S3 permissions. Hashes and distinct receipt
    # namespaces separate new releases from the original Household package.
    prefix = "household"
    archive = directory / "package.zip"
    fetch("packages", archive_hash, version, archive, prefix=prefix)
    approved, package = manifest.load_archive(archive, manifest_hash)
    category = approved["category"]
    # The archive identifies the exact immutable batch and review date on retries.
    identity = BatchIdentity("manifest-" + archive_hash, "guide-release-v1",
                             approved["verifiedOn"] + "T00:00:00Z")
    checklist = "family-visa-v1" if category in manifest.IMMIGRATION else "standard-v1"
    release = Release(prefix, category, frozenset(package), identity, manifest_hash, checklist)
    state = read_state(slugs=release.slugs)
    if state["reviewStarted"] or state["publications"]:
        receipt = import_checkpoint(directory, archive_hash, version, release)
    else:
        staged = stage(package, archive_hash, directory, release=release)
        receipt = import_checkpoint(directory, archive_hash, version, release, staged)
    return publish(package, archive_hash, receipt, approved["verifiedOn"], directory, release=release)


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
    parser.add_argument("action", choices=("import", "import-household", "publish", "publish-household", "release-manifest"))
    parser.add_argument("package_sha256")
    parser.add_argument("package_version")
    parser.add_argument("receipt_sha256", nargs="?")
    parser.add_argument("verified_on", nargs="?")
    parser.add_argument("--manifest-sha256")
    args = parser.parse_args()
    try:
        publishing = args.action in ("publish", "publish-household")
        generic = args.action == "release-manifest"
        require(generic == (args.manifest_sha256 is not None), "Manifest release requires the approved manifest digest.")
        if generic:
            require(re.fullmatch(HEX, args.manifest_sha256) is not None, "Invalid approved manifest digest.")
        require((args.receipt_sha256 is not None and args.verified_on is not None)
                if publishing else args.receipt_sha256 is None and args.verified_on is None,
                "Use the import receipt and local review date only for publication.")
        host_ready()
        with tempfile.TemporaryDirectory(prefix="lifeinuk-editorial-") as scratch:
            directory = Path(scratch)
            if generic:
                receipt = release_reviewed_archive(args.package_sha256, args.package_version, args.manifest_sha256, directory)
            elif args.action in ("import-household", "publish-household"):
                fetch("packages", args.package_sha256, args.package_version, directory / "package.zip", prefix="household")
                unpack(directory / "package.zip", directory, household.package_names())
                package = household.load_package(directory, read_json)
                if args.action == "import-household":
                    receipt = stage(package, args.package_sha256, directory, release=HOUSEHOLD)
                else:
                    receipt = publish(package, args.package_sha256, args.receipt_sha256, args.verified_on, directory,
                                      release=HOUSEHOLD)
            else:
                fetch("packages", args.package_sha256, args.package_version, directory / "package.zip")
                unpack(directory / "package.zip", directory, package_names())
                package = load_package(directory)
                if args.action == "import":
                    receipt = stage(package, args.package_sha256, directory)
                else:
                    receipt = publish(package, args.package_sha256, args.receipt_sha256, args.verified_on, directory)
        print(f"RESULT {args.action} receipt_sha256={receipt}")
    except Refused as failure:
        parser.exit(1, f"Editorial release refused: {failure}\n")
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, AttributeError,
            RuntimeError, ElementTree.ParseError):
        # Exceptions can contain remote response bodies or local sensitive file data.
        parser.exit(1, "Editorial release failed. Reconcile with the private receipt before retrying.\n")


if __name__ == "__main__":
    main()
