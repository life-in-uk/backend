#!/usr/bin/env python3
"""Unprivileged release preview displayed before the Production approval."""

from datetime import date, datetime, timezone
import os
from pathlib import Path
import re
import sys

action, *values = sys.argv[1:]
if (os.environ.get("ENABLED") != "true" or os.environ.get("GITHUB_REF") != "refs/heads/main"
        or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"):
    raise SystemExit("Editorial dispatch requires the enabled, protected main workflow.")
publishing = action in {"publish", "publish-household"}
if action not in {"import", "import-household", "publish", "publish-household"} or len(values) != (4 if publishing else 2):
    raise SystemExit("Incorrect number of editorial inputs.")
package_hash, package_version = values[:2]
if (not re.fullmatch(r"[0-9a-f]{64}", package_hash)
        or not re.fullmatch(r"[A-Za-z0-9._~+/=-]{1,1024}", package_version)
        or package_version == "null"):
    raise SystemExit("A package SHA-256 and immutable S3 version are required.")
if publishing:
    receipt_hash, verified_on = values[2:]
    if not re.fullmatch(r"[0-9a-f]{64}", receipt_hash):
        raise SystemExit("An import receipt SHA-256 is required.")
    try:
        day = date.fromisoformat(verified_on)
    except ValueError:
        raise SystemExit("A valid local review date (YYYY-MM-DD) is required.") from None
    if day.isoformat() != verified_on or day > datetime.now(timezone.utc).date():
        raise SystemExit("A valid, non-future local review date (YYYY-MM-DD) is required.")

with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
    heading = {"import-household": "Household Services & Insurance private draft import",
               "publish-household": "Household Services & Insurance publication"}.get(action, f"Education {action}")
    print(f"## {heading}\n\nPackage SHA-256: `{package_hash}`", file=summary)
    print(f"\nPackage S3 version ID: `{package_version}`", file=summary)
    if publishing:
        print(f"\nPrivate import receipt SHA-256: `{receipt_hash}`", file=summary)
        print(f"\nLocal review date: `{verified_on}`", file=summary)
        print("\nApprove the protected job only after checking these inputs against your local review.", file=summary)
