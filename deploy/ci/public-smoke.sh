#!/usr/bin/env bash
# Public API smoke test through CloudFront after a backend release (docs/ci-cd.md).
#
#   deploy/ci/public-smoke.sh https://lifeinuk.info [expected Guide count]
set -euo pipefail
base=${1:?usage: public-smoke.sh <origin> [guides]}
want=${2:-}
failed=0
check() { if eval "$2"; then echo "  ✓ $1"; else echo "  ✗ $1"; failed=1; fi; }
# curl that waits out the per-viewer rate limit (HTTP 429).
get() { local code; for _ in 1 2 3 4 5 6; do code=$(curl -sS -o "$tmp/body" -w '%{http_code}' --max-time 20 "$@" || echo 000); [ "$code" = 429 ] || break; sleep 3; done; echo "$code"; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

code=$(get "$base/api/guides"); cp "$tmp/body" "$tmp/guides"
check "/api/guides 200 ($code)" '[ "$code" = 200 ]'
count=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d) if isinstance(d, list) else -1)' "$tmp/guides" 2>/dev/null || echo -1)
check "/api/guides lists published Guides ($count)" '[ "$count" -gt 0 ]'
[ -z "$want" ] || check "Guide count equals the server's ($want)" '[ "$count" = "$want" ]'
slug=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["slug"])' "$tmp/guides" 2>/dev/null || echo none)
code=$(get "$base/api/guides/$slug"); check "Guide detail $slug 200 ($code)" '[ "$code" = 200 ]'
code=$(get "$base/api/guides/$slug/locales/en"); check "localized Guide detail 200 ($code)" '[ "$code" = 200 ]'
code=$(get "$base/api/guides/no-such-guide-smoke"); check "unknown Guide 404 with a JSON body ($code)" '[ "$code" = 404 ] && grep -q "\"code\"" "$tmp/body"'
code=$(get "$base/sitemap.xml"); check "sitemap.xml on the public origin ($code)" '[ "$code" = 200 ] && grep -q "<loc>$base/" "$tmp/body"'
code=$(get "$base/robots.txt"); check "robots.txt names the sitemap ($code)" '[ "$code" = 200 ] && grep -qx "Sitemap: $base/sitemap.xml" <(tr -d "\r" < "$tmp/body")'
code=$(get "$base/api/owner/editorial/session"); check "owner API not public ($code)" '[ "$code" = 404 ]'
code=$(get -H 'Accept-Encoding: gzip' -o /dev/null -D "$tmp/headers" "$base/api/guides"); check "API compressed" 'grep -qi "^content-encoding: gzip" "$tmp/headers"'
exit "$failed"
