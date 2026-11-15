#!/usr/bin/env python3
"""Prepare and submit the ten verified legacy Guide source batches.

The request manifest is immutable after creation and intentionally contains no API
credential. Re-submit the same manifest after an uncertain outcome: Spring's
idempotency key returns the original batch receipt instead of creating a duplicate.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

EXPECTED_SLUGS = {
    "chinese-medical-records-vaccinations-england",
    "medicines-prescriptions-england",
    "nhs-dentist-england",
    "nhs-ihs-costs-england",
    "nhs-interpreter-language-help-england",
    "open-uk-bank-account-new-arrival",
    "registering-with-a-gp-england",
    "urgent-dental-care-england",
    "visiting-parents-healthcare-england",
    "where-to-go-when-ill-england",
}
ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*\Z")
MANIFEST_VERSION = "legacy-guide-batch-manifest-v1"


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def utc_instant(value: object, label: str) -> dt.datetime:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be an offset-aware ISO timestamp")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"{label} must include a timezone")
    return parsed.astimezone(dt.timezone.utc)


def normalized(value: object, parent_key: str = "") -> object:
    if parent_key in {"publishedAt", "updatedAt", "accessedAt"} and value is not None:
        return utc_instant(value, parent_key)
    if isinstance(value, dict):
        return {key: normalized(item, key) for key, item in value.items()}
    if isinstance(value, list):
        return [normalized(item) for item in value]
    return value


def validate_identifier(value: str, label: str, max_length: int) -> None:
    if len(value) > max_length or not ID_PATTERN.fullmatch(value):
        raise ValueError(f"{label} must match Spring's accepted identifier format (max {max_length})")


def prepare(guide_dir: Path, baseline_map_path: Path, policy_version: str, source_version: str) -> dict:
    validate_identifier(policy_version, "policyVersion", 120)
    validate_identifier(source_version, "sourceVersion", 120)
    artifacts: dict[str, dict] = {}
    for path in sorted(guide_dir.glob("*-zh.json")):
        guide = json.loads(path.read_text(encoding="utf-8"))
        slug = guide.get("slug")
        if slug in artifacts:
            raise ValueError(f"Duplicate Guide artifact for {slug}")
        artifacts[slug] = guide
    if set(artifacts) != EXPECTED_SLUGS:
        raise ValueError("Guide artifact set is not exactly the ten verified legacy Guides")

    baseline_map = json.loads(baseline_map_path.read_text(encoding="utf-8"))
    entries = baseline_map.get("guides") if isinstance(baseline_map, dict) else None
    if not isinstance(entries, list):
        raise ValueError("Baseline map must contain a guides array")
    baselines = {entry.get("slug"): entry for entry in entries if isinstance(entry, dict)}
    if len(baselines) != len(entries) or set(baselines) != EXPECTED_SLUGS:
        raise ValueError("Baseline map must contain exactly one entry for each expected Guide")

    requests = []
    for slug in sorted(EXPECTED_SLUGS):
        artifact = artifacts[slug]
        baseline = baselines[slug]
        if artifact.get("status") != "PUBLISHED" or not artifact.get("publishedAt"):
            raise ValueError(f"{slug}: expected a published Chinese artifact")
        published_at = utc_instant(artifact["publishedAt"], f"{slug}.publishedAt")
        updated_at = utc_instant(artifact["updatedAt"], f"{slug}.updatedAt")
        if published_at != updated_at:
            raise ValueError(f"{slug}: publishedAt and updatedAt differ from the verified baseline condition")

        revision_id = str(uuid.UUID(baseline["revisionId"]))
        canonical_form = baseline.get("canonicalForm")
        digest = baseline.get("draftDigest")
        if not isinstance(canonical_form, str) or not isinstance(digest, str):
            raise ValueError(f"{slug}: baseline entry requires canonicalForm and draftDigest")
        actual_digest = hashlib.sha256(canonical_form.encode("utf-8")).hexdigest()
        if digest != actual_digest:
            raise ValueError(f"{slug}: baseline canonicalForm does not match draftDigest")
        canonical_guide = json.loads(canonical_form)
        if canonical_guide.pop("canonicalization", None) != "guide-content-v1":
            raise ValueError(f"{slug}: baseline canonicalization is not guide-content-v1")
        if canonical_guide.get("status") != "DRAFT" or canonical_guide.get("publishedAt") is not None:
            raise ValueError(f"{slug}: baseline revision is not an unpublished DRAFT")
        artifact_source = dict(artifact)
        artifact_source["status"] = "DRAFT"
        artifact_source["publishedAt"] = None
        if normalized(canonical_guide) != normalized(artifact_source):
            raise ValueError(f"{slug}: baseline revision differs from the exact published artifact content")
        if canonical_guide.get("slug") != slug:
            raise ValueError(f"{slug}: baseline revision slug mismatch")

        key = f"phase6a-legacy-{slug}-{digest[:16]}"
        validate_identifier(key, "idempotencyKey", 200)
        payload = {
            "idempotencyKey": key,
            "policyVersion": policy_version,
            "sourceVersion": source_version,
            "validFrom": published_at.isoformat(timespec="microseconds").replace("+00:00", "Z"),
            "validUntil": None,
            "basedOnRevisionId": revision_id,
            "source": canonical_guide,
        }
        requests.append({
            "slug": slug,
            "baselineRevisionId": revision_id,
            "expectedSourceDigest": digest,
            "payload": payload,
            "payloadSha256": hashlib.sha256(canonical_json(payload)).hexdigest(),
        })
    return {"format": MANIFEST_VERSION, "policyVersion": policy_version,
            "sourceVersion": source_version, "requests": requests}


def write_manifest(path: Path, manifest: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        try:
            path.unlink()
        except OSError:
            pass
        raise
    os.chmod(path, 0o600)


def load_manifest(path: Path) -> dict:
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode != 0o600:
        raise ValueError(f"Manifest permissions must be 600 (found {mode:o})")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("format") != MANIFEST_VERSION or not isinstance(manifest.get("requests"), list):
        raise ValueError("Unsupported or malformed request manifest")
    if {item.get("slug") for item in manifest["requests"]} != EXPECTED_SLUGS or len(manifest["requests"]) != 10:
        raise ValueError("Manifest must contain exactly the ten expected Guides")
    for item in manifest["requests"]:
        payload = item.get("payload")
        if not isinstance(payload, dict) or hashlib.sha256(canonical_json(payload)).hexdigest() != item.get("payloadSha256"):
            raise ValueError(f"Manifest payload integrity check failed for {item.get('slug')}")
        if payload.get("source", {}).get("slug") != item.get("slug"):
            raise ValueError("Manifest source slug mismatch")
        if payload.get("basedOnRevisionId") != item.get("baselineRevisionId"):
            raise ValueError("Manifest baseline revision linkage mismatch")
    return manifest


def submit(manifest: dict, base_url: str, token: str, timeout: float) -> list[dict]:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port is None:
        raise ValueError("Editorial API must be an explicit HTTP localhost URL with a port")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Editorial API URL cannot contain credentials, query, or fragment")
    if not token or "\n" in token or "\r" in token:
        raise ValueError("LIFE_IN_UK_EDITORIAL_DRAFT_TOKEN must be set")
    endpoint = base_url.rstrip("/") + "/api/editorial/guide-batches"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirectHandler())
    results = []
    for item in manifest["requests"]:
        request = urllib.request.Request(endpoint, data=canonical_json(item["payload"]), method="POST", headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
            "Accept": "application/json",
        })
        try:
            with opener.open(request, timeout=timeout) as response:
                if response.status != 200:
                    raise RuntimeError(f"{item['slug']}: API returned HTTP {response.status}")
                receipt = json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"{item['slug']}: API returned HTTP {error.code}; no automatic retry was attempted") from None
        except (urllib.error.URLError, TimeoutError, http.client.HTTPException) as error:
            raise RuntimeError(f"{item['slug']}: request outcome is uncertain ({type(error).__name__}); rerun the same manifest manually") from None
        if (receipt.get("slug") != item["slug"]
                or receipt.get("sourceRevisionId") != item["baselineRevisionId"]
                or receipt.get("sourceContentHash") != item["expectedSourceDigest"]
                or receipt.get("status") != "DRAFT"):
            raise RuntimeError(f"{item['slug']}: receipt did not confirm the expected baseline DRAFT; stop for inspection")
        results.append({"slug": item["slug"], "batchId": receipt.get("batchId"),
                        "sourceRevisionId": receipt.get("sourceRevisionId"),
                        "sourceContentHash": receipt.get("sourceContentHash"), "status": receipt.get("status")})
        print(f"{item['slug']} batch={receipt.get('batchId')} status=DRAFT")
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare", help="create a protected immutable request manifest")
    prepare_parser.add_argument("--guide-dir", type=Path, required=True)
    prepare_parser.add_argument("--baseline-map", type=Path, required=True)
    prepare_parser.add_argument("--manifest", type=Path, required=True)
    prepare_parser.add_argument("--policy-version", required=True)
    prepare_parser.add_argument("--source-version", required=True)
    submit_parser = commands.add_parser("submit", help="submit manifest requests through the protected API")
    submit_parser.add_argument("--manifest", type=Path, required=True)
    submit_parser.add_argument("--base-url", default="http://127.0.0.1:8188")
    submit_parser.add_argument("--timeout", type=float, default=15)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            manifest = prepare(args.guide_dir, args.baseline_map, args.policy_version, args.source_version)
            write_manifest(args.manifest, manifest)
            print(f"Prepared {len(manifest['requests'])} requests; manifest mode=600 path={args.manifest}")
        else:
            manifest = load_manifest(args.manifest)
            submit(manifest, args.base_url, os.environ.get("LIFE_IN_UK_EDITORIAL_DRAFT_TOKEN", ""), args.timeout)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
