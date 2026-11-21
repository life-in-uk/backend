"""Validate a reviewed Guide archive against the manifest digest approved in GitHub."""

import argparse
from datetime import date, datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import stat
import tempfile
from zipfile import BadZipFile, ZIP_DEFLATED, ZipFile

from promote import LOCALES, Refused, canonical_form, require

MANIFEST = "EDITORIAL-MANIFEST.json"
HEX = r"[0-9a-f]{64}"
GUIDE_KEY = r"[a-z0-9]+(?:-[a-z0-9]+)*"
MAX_BYTES = 8 * 1024 * 1024
MAX_GUIDES = 20

# Mirrors the routable categories and checklists in GuideSitemapController and GuideQualityGate.
DOMAINS = {
    "health-nhs": "health", "family-visa": "family-visa", "work-visa": "work-visa",
    "settlement": "settlement", "money": "money", "renting": "renting",
    "university-applications-student-visas": "university-applications-student-visas",
    "education": "education", "household-services": "household-services",
}
IMMIGRATION = {"family-visa", "work-visa", "settlement", "university-applications-student-visas"}


def review_date(value):
    day = date.fromisoformat(value)
    require(day.isoformat() == value and day <= datetime.now(timezone.utc).date(),
            "Invalid local review date.")
    return day


def read_json(raw):
    def unique(pairs):
        result = dict(pairs)
        require(len(result) == len(pairs), "Duplicate JSON field in release evidence.")
        return result
    return json.loads(raw, object_pairs_hook=unique)


def valid_slug(slug):
    return len(slug) <= 160 and re.fullmatch(GUIDE_KEY, slug) is not None


def load_archive(path, manifest_hash):
    require(re.fullmatch(HEX, manifest_hash) is not None, "Invalid approved manifest digest.")
    require(path.stat().st_size <= MAX_BYTES, "Release archive exceeds size limit.")
    with ZipFile(path) as archive:
        members = archive.infolist()
        names = {m.filename for m in members}
        require(len(members) == len(names) and MANIFEST in names
                and len(members) <= 1 + MAX_GUIDES * len(LOCALES),
                "Unexpected, missing or duplicate archive member.")
        require(sum(m.file_size for m in members) <= MAX_BYTES, "Expanded archive exceeds size limit.")
        require(all(not m.is_dir() and stat.S_IFMT(m.external_attr >> 16) in (0, stat.S_IFREG) for m in members),
                "Archive contains a non-regular file.")
        raw = archive.read(MANIFEST)
        require(sha256(raw).hexdigest() == manifest_hash, "Archive manifest does not match the approved digest.")
        manifest = read_json(raw)
        require(set(manifest) == {"schema", "category", "verifiedOn", "articles"}
                and manifest["schema"] == "guide-release-v1" and manifest["category"] in DOMAINS,
                "Invalid release manifest or unsupported Guide category.")
        review_date(manifest["verifiedOn"])
        category = manifest["category"]
        articles = manifest["articles"]
        require(isinstance(articles, dict) and 0 < len(articles) <= MAX_GUIDES
                and all(valid_slug(slug) for slug in articles),
                "Invalid or empty manifest slug list.")
        expected = {MANIFEST} | {f"guides/{slug}/{locale}.json" for slug in articles for locale in LOCALES}
        require(names == expected, "Archive does not contain exactly the approved Guide variants.")
        package = {}
        for slug, records in sorted(articles.items()):
            require(set(records) == set(LOCALES), f"{slug}: all five manifest locales are required.")
            guides = {}
            for locale in LOCALES:
                record = records[locale]
                require(set(record) == {"fileSha256", "contentSha256"}
                        and all(re.fullmatch(HEX, value) for value in record.values()),
                        f"{slug}/{locale}: invalid manifest hashes.")
                raw = archive.read(f"guides/{slug}/{locale}.json")
                require(sha256(raw).hexdigest() == record["fileSha256"], f"{slug}/{locale}: file hash mismatch.")
                guide = read_json(raw)
                require(guide["slug"] == slug and guide["category"] == category
                        and guide["status"] == "DRAFT" and guide.get("publishedAt") is None,
                        f"{slug}/{locale}: Guide identity or draft status differs from the manifest.")
                content_hash = sha256(canonical_form(guide).encode()).hexdigest()
                require(content_hash == record["contentSha256"], f"{slug}/{locale}: canonical content hash mismatch.")
                guides[locale] = (guide, content_hash)
            package[slug] = guides
    return manifest, package


def pack(root, output, category, verified_on, slugs):
    require(len(slugs) == len(set(slugs)) and 0 < len(slugs) <= MAX_GUIDES and all(map(valid_slug, slugs)),
            "Invalid or duplicate Guide slugs.")
    review_date(verified_on)
    release = {"schema": "guide-release-v1", "category": category, "verifiedOn": verified_on, "articles": {}}
    files = {}
    total = 0
    for slug in sorted(slugs):
        records = {}
        for locale in LOCALES:
            name = f"guides/{slug}/{locale}.json"
            with (root / name).open("rb") as file:
                raw = file.read(MAX_BYTES - total + 1)
            total += len(raw)
            require(total <= MAX_BYTES, "Expanded archive exceeds size limit.")
            guide = read_json(raw)
            records[locale] = {"fileSha256": sha256(raw).hexdigest(),
                               "contentSha256": sha256(canonical_form(guide).encode()).hexdigest()}
            files[name] = raw
        release["articles"][slug] = records
    data = (json.dumps(release, indent=2, sort_keys=True) + "\n").encode()
    manifest_hash = sha256(data).hexdigest()
    with tempfile.TemporaryDirectory(prefix="lifeinuk-guide-pack-") as temp:
        archive_path = Path(temp) / "package.zip"
        with ZipFile(archive_path, "w", compression=ZIP_DEFLATED) as archive:
            archive.writestr(MANIFEST, data)
            for name, raw in files.items():
                archive.writestr(name, raw)
        load_archive(archive_path, manifest_hash)
        with output.open("xb") as archive:
            archive.write(archive_path.read_bytes())
    return manifest_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    build = commands.add_parser("pack", help="make an archive from explicitly named local drafts")
    build.add_argument("root", type=Path)
    build.add_argument("archive", type=Path)
    build.add_argument("--category", choices=DOMAINS, required=True)
    build.add_argument("--verified-on", required=True)
    build.add_argument("--slug", action="append", required=True)
    check = commands.add_parser("check", help="verify an archive against an independently recorded manifest digest")
    check.add_argument("archive", type=Path)
    check.add_argument("manifest_sha256")
    args = parser.parse_args()
    try:
        manifest_hash = (pack(args.root, args.archive, args.category, args.verified_on, args.slug)
                         if args.action == "pack" else args.manifest_sha256)
        approved, package = load_archive(args.archive, manifest_hash)
        print(json.dumps({"category": approved["category"], "verifiedOn": approved["verifiedOn"],
                          "slugs": sorted(package), "locales": LOCALES, "manifestSha256": manifest_hash,
                          "packageSha256": sha256(args.archive.read_bytes()).hexdigest()}, indent=2))
    except Refused as error:
        parser.exit(1, f"Editorial package refused: {error}\n")
    except (BadZipFile, OSError, ValueError, KeyError, TypeError, AttributeError):
        parser.exit(1, "Editorial package refused: invalid archive, manifest or input files.\n")


if __name__ == "__main__":
    main()
