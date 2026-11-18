#!/usr/bin/env python3
"""Flyway migration safety checks (docs/ci-cd.md). Standard library only.

  check-migrations.py --base <git ref>
      CI: compared with <ref>, no existing migration was changed, renamed or
      removed (production has applied them; Flyway would refuse to start), new
      ones are named V<n>__<words>.sql with versions above every existing one,
      and statements that can lose data or break the running release are
      reported. Exit 1 on a rule violation; flagged statements are warnings.

  check-migrations.py --pending-after <applied version> [--json]
      Deploy gate: the migrations of this checkout newer than the version
      production has applied, each with its flagged statements.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

MIGRATIONS = "src/main/resources/db/migration"
NAME = re.compile(r"^V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql$")

# Statements that destroy data, or change the schema in a way the previous
# release (still running during a deploy, and the rollback target) cannot use.
RISKS = [
    ("drops an object", re.compile(r"\bDROP\s+(TABLE|COLUMN|SCHEMA|VIEW|MATERIALIZED\s+VIEW|TYPE|FUNCTION|PROCEDURE|TRIGGER|INDEX|CONSTRAINT|SEQUENCE|EXTENSION)\b", re.I)),
    ("truncates a table", re.compile(r"\bTRUNCATE\b", re.I)),
    ("deletes rows", re.compile(r"\bDELETE\s+FROM\b", re.I)),
    ("rewrites rows", re.compile(r"\bUPDATE\s+[\w.\"]+\s+SET\b", re.I)),
    ("renames an object", re.compile(r"\bRENAME\b", re.I)),
    ("changes a column type", re.compile(r"\bALTER\s+COLUMN\s+[\w\"]+\s+(SET\s+DATA\s+)?TYPE\b", re.I)),
    ("adds NOT NULL to an existing column", re.compile(r"\bALTER\s+COLUMN\s+[\w\"]+\s+SET\s+NOT\s+NULL\b", re.I)),
]


def strip_sql_comments(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return re.sub(r"--[^\n]*", " ", sql)


def risks(sql):
    """[(reason, statement excerpt)] for every flagged statement."""
    found = []
    for statement in strip_sql_comments(sql).split(";"):
        text = " ".join(statement.split())
        for reason, pattern in RISKS:
            if pattern.search(text):
                found.append((reason, text[:160]))
    return found


def version(name):
    match = NAME.match(name)
    return int(match.group(1)) if match else None


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def check_against_base(base):
    errors, warnings = [], []
    changes = [line.split("\t") for line in git("diff", "--name-status", "--no-renames", base, "--", MIGRATIONS).splitlines()]
    base_files = {Path(path).name for path in git("ls-tree", "--name-only", f"{base}:{MIGRATIONS}").split()}
    base_max = max((version(name) or 0 for name in base_files), default=0)
    for status, path in changes:
        name = Path(path).name
        if status != "A":
            errors.append(f"{name}: existing migration {'deleted' if status == 'D' else 'modified'}; production has applied it, add a new migration instead")
            continue
        number = version(name)
        if number is None:
            errors.append(f"{name}: new migrations must be named V<n>__<description>.sql")
            continue
        if number <= base_max:
            errors.append(f"{name}: version {number} is not above the latest existing version {base_max} (out-of-order migrations are disabled)")
        for reason, excerpt in risks(Path(path).read_text(encoding="utf-8")):
            warnings.append(f"{name}: {reason}: {excerpt}")
    seen = {}
    for path in sorted(Path(MIGRATIONS).glob("*.sql")):
        number = version(path.name)
        if number is None:
            errors.append(f"{path.name}: not a versioned migration name")
        elif number in seen:
            errors.append(f"{path.name}: version {number} also used by {seen[number]}")
        else:
            seen[number] = path.name
    return errors, warnings, sorted(name for status, path in changes if status == "A" for name in [Path(path).name])


def pending_after(applied):
    result = []
    for path in sorted(Path(MIGRATIONS).glob("*.sql"), key=lambda p: version(p.name) or 0):
        number = version(path.name)
        if number is not None and number > applied:
            result.append({"version": number, "file": path.name,
                           "risks": [f"{reason}: {excerpt}" for reason, excerpt in risks(path.read_text(encoding="utf-8"))]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--base")
    mode.add_argument("--pending-after", type=int)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    if args.pending_after is not None:
        pending = pending_after(args.pending_after)
        if args.json:
            print(json.dumps(pending))
        else:
            for row in pending:
                print(f"V{row['version']} {row['file']}" + "".join(f"\n  ! {risk}" for risk in row["risks"]))
        return 0

    errors, warnings, added = check_against_base(args.base)
    print(f"Migrations: {len(added)} new since {args.base}{': ' + ', '.join(added) if added else ''}")
    for warning in warnings:
        print(f"::warning title=Migration needs owner review::{warning}")
    for error in errors:
        print(f"::error title=Unsafe migration change::{error}")
    if warnings:
        print("Flagged statements block the production deploy until the owner approves them (docs/ci-cd.md).")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
