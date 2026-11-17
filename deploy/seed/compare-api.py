#!/usr/bin/env python3
"""Compares the public read API of two backends, response by response.

    compare-api.py LEFT RIGHT [--ignore-key KEY ...] [--skip PATH ...] [--report FILE]

Requests every public content endpoint on both (Guide lists in the five
locales, every localized Guide detail, every Guide in the default list,
sitemap.xml, robots.txt, Bank Holidays) and requires the same status and the
same body. JSON bodies are compared after parsing, so formatting does not
matter; --ignore-key drops a key wherever it appears (for example
sourceRevisionId, which differs when content is replayed instead of copied).

Used for the first deployment (local backend vs. the restored seed: must be
identical, including IDs) and after a production content release (local vs.
production through the SSH tunnel, see deploy/PUBLISHING.md). Exit status 1
if anything differs. Uses only the Python standard library.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

LOCALES = ["zh-CN", "zh-TW", "en", "ur", "hi"]


def fetch(base, path):
    request = urllib.request.Request(base.rstrip("/") + path, headers={"Accept-Encoding": "identity", "User-Agent": "lifeinuk-compare"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def normalize(body, ignore):
    try:
        value = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return body

    def strip(node):
        if isinstance(node, dict):
            return {k: strip(v) for k, v in node.items() if k not in ignore}
        if isinstance(node, list):
            return [strip(v) for v in node]
        return node

    return json.dumps(strip(value), sort_keys=True, ensure_ascii=False).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--ignore-key", action="append", default=[])
    parser.add_argument("--skip", action="append", default=[], help="a path not to compare (e.g. /sitemap.xml when the origins differ)")
    parser.add_argument("--report")
    args = parser.parse_args()
    ignore = set(args.ignore_key)

    paths = ["/api/guides", "/sitemap.xml", "/robots.txt", "/api/bank-holidays"]
    counts = {}
    for locale in LOCALES:
        path = f"/api/guides/locales/{locale}"
        status, body = fetch(args.left, path)
        if status != 200:
            sys.exit(f"{args.left}{path}: HTTP {status}")
        slugs = [guide["slug"] for guide in json.loads(body)]
        counts[locale] = len(slugs)
        paths.append(path)
        paths += [f"/api/guides/{slug}/locales/{locale}" for slug in slugs]
    status, body = fetch(args.left, "/api/guides")
    paths += [f"/api/guides/{guide['slug']}" for guide in json.loads(body)] if status == 200 else []

    paths = [path for path in paths if path not in set(args.skip)]
    differences = []
    for path in paths:
        left_status, left_body = fetch(args.left, path)
        right_status, right_body = fetch(args.right, path)
        if left_status != right_status:
            differences.append(f"{path}: status {left_status} vs {right_status}")
        elif normalize(left_body, ignore) != normalize(right_body, ignore):
            differences.append(f"{path}: body differs ({len(left_body)} vs {len(right_body)} bytes)")

    lines = [f"compared {len(paths)} endpoints: {args.left} vs {args.right}",
             "localized Guides per locale: " + ", ".join(f"{k}={v}" for k, v in counts.items()),
             f"ignored keys: {', '.join(sorted(ignore)) or 'none'}; skipped: {', '.join(args.skip) or 'none'}",
             f"differences: {len(differences)}"] + [f"  - {d}" for d in differences]
    text = "\n".join(lines)
    print(text)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    sys.exit(1 if differences else 0)


if __name__ == "__main__":
    main()
