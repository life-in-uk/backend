#!/usr/bin/env python3
"""Markdown summary of the Maven Surefire reports (CI job summary).

  test-summary.py [target/surefire-reports] >> "$GITHUB_STEP_SUMMARY"

Lists totals and every failed or errored test with its message. Exit 0 always:
Maven's own exit status decides whether the build failed.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

reports = Path(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports")
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
time = 0.0
problems = []
for report in sorted(reports.glob("TEST-*.xml")):
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, 0))
    time += float(suite.get("time", 0) or 0)
    for case in suite.iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is not None:
                message = " ".join((node.get("message") or node.get("type") or "").split())[:300]
                problems.append(f"| {kind} | `{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}` | {message.replace('|', '/')} |")

if not totals["tests"]:
    print("### Backend tests\n\nNo Surefire reports found.")
    sys.exit(0)
passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
state = "passed" if not (totals["failures"] or totals["errors"]) else "FAILED"
print(f"### Backend tests {state}\n")
print(f"{totals['tests']} tests: {passed} passed, {totals['failures']} failed, {totals['errors']} errors, "
      f"{totals['skipped']} skipped ({time:.0f} s in test classes)\n")
if problems:
    print("| | Test | Message |\n|---|---|---|")
    print("\n".join(problems[:50]))
