#!/usr/bin/env bash
# End-to-end test of deploy/ on the workstation with Docker; no AWS, no server.
#
#   deploy/test/stack-test.sh <seed.dump> [--keep]
#
# Runs the real deploy/compose.yml as project "lifeinuk-stacktest" with
# temporary env files, a self-signed certificate for origin.test and a
# temporary backup directory, then checks: seed restore into an empty database
# (and its refusals), Flyway validation without migration, the origin
# protections in nginx (TLS, X-Origin-Verify, path and method allowlist, JSON
# errors, rate limit, gzip), that backend JSON errors pass through, that the
# editorial API is unconfigured (503), that the stack serves exactly the local
# backend's public content, memory limits, that the database has no published
# port, the nightly backup, the restore drill and a full production-style
# replace. Removes everything it created unless --keep is given.
#
# Needs: Docker, a built jar (target/), python3, curl, openssl, and the local
# backend on :8188 for the content comparison (COMPARE_API to change it).
set -euo pipefail
repo=$(git -C "$(dirname "$(readlink -f "$0")")" rev-parse --show-toplevel)
seed=${1:?usage: stack-test.sh <seed.dump> [--keep]}
keep=${2:-}
seed=$(readlink -f "$seed")
[ -f "$seed.sha256" ] || { echo "$seed.sha256 missing" >&2; exit 2; }
compare_api=${COMPARE_API:-http://127.0.0.1:8188}

tmp=$(mktemp -d)
export LIFEINUK_DIR="$repo/deploy" LIFEINUK_PROJECT=lifeinuk-stacktest
export LIFEINUK_ETC="$tmp/etc" LIFEINUK_CERTS="$tmp/letsencrypt" LIFEINUK_BACKUPS="$tmp/backups"
export LIFEINUK_APP_BIND=127.0.0.1:18080 LIFEINUK_PROXY_BIND=127.0.0.1:18443
export APP_IMAGE=lifeinuk-backend:stacktest
source "$repo/deploy/scripts/common.sh"
pg_image=$(sed -n 's/^ *image: \(postgres:16[^ ]*\)$/\1/p' "$repo/deploy/compose.yml")

passed=0; failed=0
pass() { passed=$((passed + 1)); printf '  ✓ %s\n' "$*"; }
fail() { failed=$((failed + 1)); printf '  ✗ %s\n' "$*"; }
expect() { local name=$1 want=$2 got=$3; [ "$got" = "$want" ] && pass "$name" || fail "$name (expected $want, got $got)"; }

cleanup() {
  if [ "$keep" = --keep ]; then echo "kept: project $LIFEINUK_PROJECT, files $tmp"; return; fi
  compose --profile tools down -v --remove-orphans >/dev/null 2>&1 || true
  # Whatever compose could not remove (for example after a failed setup), by project label.
  local label="com.docker.compose.project=$LIFEINUK_PROJECT"
  docker ps -aq --filter "label=$label" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=$label" | xargs -r docker volume rm >/dev/null 2>&1 || true
  docker network ls -q --filter "label=$label" | xargs -r docker network rm >/dev/null 2>&1 || true
  # Files written by the container's root user: remove them from a container.
  docker run --rm -v "$tmp:/t" --entrypoint sh "$pg_image" -c 'rm -rf /t/backups /t/letsencrypt /t/etc' >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

echo "▶ setup (project $LIFEINUK_PROJECT, $tmp)"
docker build -q --build-arg REVISION=stacktest -t "$APP_IMAGE" "$repo" >/dev/null
mkdir -p "$LIFEINUK_ETC" "$LIFEINUK_BACKUPS" "$LIFEINUK_CERTS/live/origin.test"
chmod 777 "$LIFEINUK_BACKUPS"
app_pw=$(openssl rand -hex 32); secret=$(openssl rand -hex 32)
printf 'POSTGRES_PASSWORD=%s\nAPP_DB_PASSWORD=%s\n' "$(openssl rand -hex 32)" "$app_pw" > "$LIFEINUK_ETC/db.env"
sed -e "s/^DB_PASSWORD=$/DB_PASSWORD=$app_pw/" "$repo/deploy/env/backend.env.example" > "$LIFEINUK_ETC/backend.env"
printf 'ORIGIN_SERVER_NAME=origin.test\nORIGIN_VERIFY_SECRET=%s\n' "$secret" > "$LIFEINUK_ETC/proxy.env"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=origin.test -addext subjectAltName=DNS:origin.test \
  -keyout "$LIFEINUK_CERTS/live/origin.test/privkey.pem" -out "$LIFEINUK_CERTS/live/origin.test/fullchain.pem" 2>/dev/null
cp "$seed" "$LIFEINUK_BACKUPS/seed.dump"
(cd "$LIFEINUK_BACKUPS" && sed 's/ .*$/  seed.dump/' "$seed.sha256" > seed.dump.sha256)

echo "▶ database and seed"
compose up -d db >/dev/null 2>&1
wait_healthy db 90
"$repo/deploy/scripts/seed-restore.sh" "$LIFEINUK_BACKUPS/seed.dump" 2>&1 | sed 's/^/    /'
expect "seed restored: 50 published Guides" 50 "$(db_sql postgres life_in_uk "SELECT count(*) FROM guide WHERE status = 'PUBLISHED'")"
expect "tables owned by the application role" 0 "$(db_sql postgres life_in_uk "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'life_in_uk_app'")"
if "$repo/deploy/scripts/seed-restore.sh" "$LIFEINUK_BACKUPS/seed.dump" >/dev/null 2>&1; then fail "a second seed restore was accepted"; else pass "a seed is refused when the database has tables"; fi
cp "$LIFEINUK_BACKUPS/seed.dump" "$LIFEINUK_BACKUPS/tampered.dump"; printf 'x' >> "$LIFEINUK_BACKUPS/tampered.dump"
sed 's/seed.dump/tampered.dump/' "$LIFEINUK_BACKUPS/seed.dump.sha256" > "$LIFEINUK_BACKUPS/tampered.dump.sha256"
if "$repo/deploy/scripts/seed-restore.sh" "$LIFEINUK_BACKUPS/tampered.dump" >/dev/null 2>&1; then fail "a tampered seed was accepted"; else pass "a seed with a wrong checksum is refused"; fi
rm -f "$LIFEINUK_BACKUPS"/tampered.dump*

echo "▶ app and proxy"
compose up -d app >/dev/null 2>&1
wait_healthy app 180
app_log=$(compose logs app 2>&1)
grep -q 'Successfully validated 15 migrations' <<<"$app_log" && pass "Flyway validated 15 migrations" || fail "Flyway validation not logged"
grep -q -E 'Migrating schema|Successfully applied' <<<"$app_log" && fail "Flyway migrated the seeded database" || pass "Flyway applied nothing"
compose up -d proxy >/dev/null 2>&1
for _ in $(seq 1 30); do curl -sk -o /dev/null --resolve origin.test:18443:127.0.0.1 https://origin.test:18443/ && break; sleep 1; done

origin=https://origin.test:18443
req() { curl -s --resolve origin.test:18443:127.0.0.1 --cacert "$LIFEINUK_CERTS/live/origin.test/fullchain.pem" "$@" || true; }
status() { req -o /dev/null -w '%{http_code}' "$@"; }
H=(-H "X-Origin-Verify: $secret")

echo "▶ origin protection (nginx)"
expect "TLS with the origin certificate; no secret header → 403" 403 "$(status "$origin/api/guides")"
expect "wrong secret → 403" 403 "$(status -H 'X-Origin-Verify: nope' "$origin/api/guides")"
expect "403 body is JSON" '{"code":"ORIGIN_FORBIDDEN"}' "$(req "$origin/api/guides")"
expect "secret header → /api/guides 200" 200 "$(status "${H[@]}" "$origin/api/guides")"
expect "API gzip" gzip "$(req "${H[@]}" -H 'Accept-Encoding: gzip' -o /dev/null -w '%header{content-encoding}' "$origin/api/guides")"
body=$(req "${H[@]}" "$origin/api/guides/no-such-guide")
expect "backend 404 passes through" 404 "$(status "${H[@]}" "$origin/api/guides/no-such-guide")"
python3 -c 'import json,sys; b=json.loads(sys.argv[1]); sys.exit(b == {"code": "NOT_FOUND"})' "$body" \
  && pass "backend 404 keeps its own JSON body ($body)" || fail "backend 404 body was replaced: $body"
expect "owner API blocked" 404 "$(status "${H[@]}" "$origin/api/owner/editorial/session")"
expect "editorial API blocked" 404 "$(status "${H[@]}" "$origin/api/editorial/guide-batches/x")"
expect "blocked path answers JSON" '{"code":"NOT_FOUND"}' "$(req "${H[@]}" "$origin/api/owner/editorial/batches")"
expect "POST to the API refused" 403 "$(status "${H[@]}" -X POST -d '{}' "$origin/api/guides")"
expect "actuator not exposed" 404 "$(status "${H[@]}" "$origin/actuator/health")"
expect "other paths 404" 404 "$(status "${H[@]}" "$origin/en/renting")"
sitemap=$(req "${H[@]}" "$origin/sitemap.xml")
expect "sitemap: 290 URLs" 290 "$(grep -o '<loc>' <<<"$sitemap" | wc -l | tr -d ' ')"
expect "sitemap: all on https://lifeinuk.info" 0 "$(grep -o '<loc>[^<]*' <<<"$sitemap" | grep -vc '^<loc>https://lifeinuk.info/' || true)"
expect "robots.txt sitemap line" "Sitemap: https://lifeinuk.info/sitemap.xml" "$(req "${H[@]}" "$origin/robots.txt" | grep '^Sitemap:')"
codes=$(for _ in $(seq 1 40); do status "${H[@]}" "$origin/api/places/search?q=london"; echo; done | sort | uniq -c | tr -s ' ' | tr '\n' ';')
grep -q ' 429' <<<"$codes" && pass "place search is rate-limited ($codes)" || fail "no 429 from place search ($codes)"

echo "▶ app (loopback port for operators)"
expect "editorial API not configured → 503" 503 "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/editorial/guide-batches/x)"
# From an allowed origin (the backend's default list), so the check reaches "not configured".
expect "owner login not configured → 503" 503 "$(curl -s -o /dev/null -w '%{http_code}' -H 'Origin: http://127.0.0.1:5173' -H 'Content-Type: application/json' -d '{"role":"DRAFT","credential":"x"}' http://127.0.0.1:18080/api/owner/editorial/session)"
if python3 "$repo/deploy/seed/compare-api.py" "$compare_api" http://127.0.0.1:18080 --skip /sitemap.xml --skip /robots.txt > "$tmp/compare.txt"; then
  pass "same public content as $compare_api ($(head -1 "$tmp/compare.txt" | cut -d: -f1))"
else fail "content differs from $compare_api: $(cat "$tmp/compare.txt")"; fi

echo "▶ containers"
for pair in db:1073741824 app:1610612736 proxy:134217728; do
  service=${pair%%:*}
  expect "$service memory limit" "${pair#*:}" "$(docker inspect -f '{{.HostConfig.Memory}}' "$(compose ps -q "$service")")"
done
expect "database has no published port" "" "$(docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{$p}}{{end}}' "$(compose ps -q db)")"
expect "app published on loopback only" 127.0.0.1 "$(docker inspect -f '{{(index (index .HostConfig.PortBindings "8080/tcp") 0).HostIp}}' "$(compose ps -q app)")"
expect "app runs as uid 10001" 10001 "$(compose exec -T app id -u | tr -d '\r')"

echo "▶ backup and restore"
backup=$("$repo/deploy/scripts/backup.sh" 2>/dev/null) && [ -f "$backup" ] && pass "backup written: $(basename "$backup")" || fail "backup failed"
(cd "$LIFEINUK_BACKUPS" && sha256sum --quiet -c "$(basename "$backup").sha256") && pass "backup checksum" || fail "backup checksum"
drill=$("$repo/deploy/scripts/restore.sh" check "$backup" 2>&1 | tail -1)
grep -q 'restore drill OK: guides=50 localized=250 open_publications=50 bank_holiday_artifacts=1 flyway=V15' <<<"$drill" \
  && pass "restore drill: ${drill#*OK: }" || fail "restore drill: $drill"
expect "restore drill leaves no scratch database" 0 "$(db_sql postgres postgres "SELECT count(*) FROM pg_database WHERE datname = 'life_in_uk_restore_check'")"
if "$repo/deploy/scripts/restore.sh" replace "$backup" >/dev/null 2>&1; then fail "replace ran without confirmation"; else pass "replace needs --confirm-replace-production"; fi
"$repo/deploy/scripts/restore.sh" replace "$backup" --confirm-replace-production > "$tmp/replace.log" 2>&1 \
  && pass "replace production from backup; app healthy again" || fail "replace failed: $(tail -3 "$tmp/replace.log")"
expect "previous database kept" 1 "$(db_sql postgres postgres "SELECT count(*) FROM pg_database WHERE datname LIKE 'life_in_uk_before_%'")"
expect "pre-restore backup taken" 1 "$(ls "$LIFEINUK_BACKUPS" | grep -c 'before-restore.dump$')"
python3 "$repo/deploy/seed/compare-api.py" "$compare_api" http://127.0.0.1:18080 --skip /sitemap.xml --skip /robots.txt > "$tmp/compare2.txt" \
  && pass "content unchanged after replace" || fail "content differs after replace: $(cat "$tmp/compare2.txt")"
if "$repo/deploy/scripts/seed-restore.sh" "$LIFEINUK_BACKUPS/seed.dump" >/dev/null 2>&1; then fail "seed restore accepted while the app runs"; else pass "seed restore refused while the app runs"; fi

echo
echo "stack test: $passed passed, $failed failed"
[ "$failed" = 0 ]
