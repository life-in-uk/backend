#!/usr/bin/env python3
"""Unprivileged release preview displayed before the Production approval."""

import os
from pathlib import Path
import re
import sys

action, *values = sys.argv[1:]
if (os.environ.get("ENABLED") != "true" or os.environ.get("GITHUB_REF") != "refs/heads/main"
        or os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"):
    raise SystemExit("Editorial dispatch requires the enabled, protected main workflow.")
if action not in {"import", "publish"} or len(values) != (2 if action == "import" else 4):
    raise SystemExit("Incorrect number of editorial inputs.")
for hash_value, version in zip(values[::2], values[1::2]):
    if not re.fullmatch(r"[0-9a-f]{64}", hash_value) or not re.fullmatch(r"[A-Za-z0-9._~+/=-]{1,1024}", version) or version == "null":
        raise SystemExit("A SHA-256 and immutable S3 version are required for each input.")
with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
    print(f"## Education {action}\n\nPackage SHA-256: \`{values[0]}\`", file=summary)
    if action == "publish":
        print(f"\nSigned review archive SHA-256: \`{values[2]}\`", file=summary)
