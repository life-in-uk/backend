#!/usr/bin/env bash
# CI test of the production image and the release tooling (docs/ci-cd.md).
# Docker only: no AWS, no seed, no local backend. Used by .github/workflows/ci.yml.
#
#   deploy/test/ci-stack-test.sh lifeinuk-backend:<commit12>   (built by deploy/scripts/build-image.sh)
#
# Runs the real deploy/compose.yml (copied to a temporary /opt/lifeinuk) with
# temporary env files and a self-signed origin certificate, then checks:
#   - the image starts on an empty database and Flyway applies every migration;
#   - origin protections in nginx (TLS, X-Origin-Verify, allowlist, JSON
#     errors, gzip), editorial API unconfigured, container hardening;
#   - deploy/scripts/release.sh: status, refusals (deploy-files drift, wrong
#     archive checksum, unapproved migration, rollback across a newer schema),
#     deploy, idempotent redeploy, approved migration, rollback with the newer
#     schema, automatic return to the previous image when a release is
#     unhealthy, roll-forward, the lock, image pruning, the audit log, and that
#     database rows survive every step.
# Removes everything it created unless KEEP=1.
set -euo pipefail
repo=$(git -C "$(dirname "$(readlink -f "$0")")" rev-parse --show-toplevel)
image=${1:?usage: ci-stack-test.sh lifeinuk-backend:<commit12>}
docker image inspect "$image" >/dev/null
commit_a=$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")
[[ "$commit_a" =~ ^[0-9a-f]{40}$ ]] || { echo "$image has no 40-character revision label" >&2; exit 2; }

tmp=$(mktemp -d)
mkdir -p "$tmp/opt" "$tmp/etc" "$tmp/backups" "$tmp/letsencrypt/live/origin.test" "$tmp/releases" "$tmp/work"
cp -r "$repo/deploy/compose.yml" "$repo/deploy/nginx" "$repo/deploy/db" "$repo/deploy/scripts" "$repo/deploy/systemd" "$tmp/opt/"
export LIFEINUK_DIR="$tmp/opt" LIFEINUK_PROJECT=lifeinuk-citest
export LIFEINUK_ETC="$tmp/etc" LIFEINUK_CERTS="$tmp/letsencrypt" LIFEINUK_BACKUPS="$tmp/backups"
export LIFEINUK_APP_BIND=127.0.0.1:18080 LIFEINUK_PROXY_BIND=127.0.0.1:18443
export RELEASE_ENV=/dev/null RELEASE_SOURCE="$tmp/releases" RELEASE_WORK="$tmp/work" RELEASE_LOCK="$tmp/release.lock"
export RELEASE_HEALTH_SECONDS=240 KEEP_IMAGES=3
# A repository of its own: release.sh prunes old tags of IMAGE_REPO, and must never touch real images.
export IMAGE_REPO=lifeinuk-citest
unset APP_IMAGE # the compose project reads it from $LIFEINUK_DIR/.env, as on the server
source "$repo/deploy/scripts/common.sh"
release="$LIFEINUK_DIR/scripts/release.sh"
pg_image=$(sed -n 's/^ *image: \(postgres:16[^ ]*\)$/\1/p' "$repo/deploy/compose.yml")

passed=0; failed=0
pass() { passed=$((passed + 1)); printf '  ✓ %s\n' "$*"; }
fail() { failed=$((failed + 1)); printf '  ✗ %s\n' "$*"; }
expect() { local name=$1 want=$2 got=$3; [ "$got" = "$want" ] && pass "$name" || fail "$name (expected $want, got $got)"; }
refused() { local name=$1 pattern=$2; shift 2; local out; if out=$("$@" 2>&1); then fail "$name (accepted)"; elif grep -q -- "$pattern" <<<"$out"; then pass "$name"; else fail "$name (wrong reason: $(tail -1 <<<"$out"))"; fi; }
field() { sed -n "s/^$1=//p" <<<"$2"; }
current() { sed -n 's/^APP_IMAGE=//p' "$LIFEINUK_DIR/.env"; }
tags=()

cleanup() {
  if [ "${KEEP:-}" = 1 ]; then echo "kept: project $LIFEINUK_PROJECT, files $tmp"; return; fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  local label="com.docker.compose.project=$LIFEINUK_PROJECT"
  docker ps -aq --filter "label=$label" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=$label" | xargs -r docker volume rm >/dev/null 2>&1 || true
  for tag in "${tags[@]}"; do docker image rm "$tag" >/dev/null 2>&1 || true; done
  docker run --rm -v "$tmp:/t" --entrypoint sh "$pg_image" -c 'rm -rf /t/backups /t/letsencrypt /t/etc /t/work' >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

# A release archive in $RELEASE_SOURCE/<commit>/, as the deploy workflow uploads it; prints its sha256.
archive() {
  local tag=$1 commit=$2 dir="$RELEASE_SOURCE/$2" name="$IMAGE_REPO-${2:0:12}.tar.gz"
  mkdir -p "$dir"
  docker save "$tag" | gzip -1 > "$dir/$name"
  sha256sum "$dir/$name" | cut -c1-64
}
# An image FROM $image with another revision label and optional extra Dockerfile lines.
variant() {
  local commit=$1 extra=${2:-} ctx
  ctx=$(mktemp -d "$tmp/ctx.XXXX")
  [ ! -f "$tmp/app.jar" ] || cp "$tmp/app.jar" "$ctx/"
  printf 'FROM %s\nLABEL org.opencontainers.image.revision=%s\n%s\n' "$image" "$commit" "$extra" > "$ctx/Dockerfile"
  docker build -q --pull=false -t "$IMAGE_REPO:${commit:0:12}" "$ctx" >/dev/null
  tags+=("$IMAGE_REPO:${commit:0:12}")
  rm -rf "$ctx"
}

echo "▶ setup (project $LIFEINUK_PROJECT, $tmp)"
app_pw=$(openssl rand -hex 32); secret=$(openssl rand -hex 32)
printf 'POSTGRES_PASSWORD=%s\nAPP_DB_PASSWORD=%s\n' "$(openssl rand -hex 32)" "$app_pw" > "$LIFEINUK_ETC/db.env"
sed -e "s/^DB_PASSWORD=$/DB_PASSWORD=$app_pw/" "$repo/deploy/env/backend.env.example" > "$LIFEINUK_ETC/backend.env"
printf 'ORIGIN_SERVER_NAME=origin.test\nORIGIN_VERIFY_SECRET=%s\n' "$secret" > "$LIFEINUK_ETC/proxy.env"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=origin.test -addext subjectAltName=DNS:origin.test \
  -keyout "$LIFEINUK_CERTS/live/origin.test/privkey.pem" -out "$LIFEINUK_CERTS/live/origin.test/fullchain.pem" 2>/dev/null
chmod 777 "$LIFEINUK_BACKUPS"

# Release candidates: B (same code, new revision), C (adds an additive migration), D (never becomes healthy).
commit_b=$(printf 'b%.0s' {1..40}); commit_c=$(printf 'c%.0s' {1..40}); commit_d=$(printf 'd%.0s' {1..40})
variant "$commit_b"
cid=$(docker create "$image"); docker cp -q "$cid:/app/app.jar" "$tmp/app.jar"; docker rm "$cid" >/dev/null
next=$(( $(python3 "$repo/deploy/ci/check-migrations.py" --pending-after 0 --json | python3 -c 'import json,sys; print(max(r["version"] for r in json.load(sys.stdin)))') + 1 ))
python3 - "$tmp/app.jar" "$next" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_STORED) as jar:
    jar.writestr(f"BOOT-INF/classes/db/migration/V{sys.argv[2]}__ci_release_test.sql", "CREATE TABLE ci_release_test (note text NOT NULL);\n")
PY
variant "$commit_c" "COPY app.jar /app/app.jar"
rm -f "$tmp/app.jar"
variant "$commit_d" 'ENTRYPOINT ["sh", "-c", "echo broken release; exit 1"]'
sha_b=$(archive "$IMAGE_REPO:${commit_b:0:12}" "$commit_b")
sha_c=$(archive "$IMAGE_REPO:${commit_c:0:12}" "$commit_c")
sha_d=$(archive "$IMAGE_REPO:${commit_d:0:12}" "$commit_d")
for c in "$commit_b" "$commit_c" "$commit_d"; do docker image rm "$IMAGE_REPO:${c:0:12}" >/dev/null; done
digest=$(deploy_files_digest "$repo/deploy")
tag_a="$IMAGE_REPO:${commit_a:0:12}" tag_b="$IMAGE_REPO:${commit_b:0:12}" tag_c="$IMAGE_REPO:${commit_c:0:12}" tag_d="$IMAGE_REPO:${commit_d:0:12}"
docker tag "$image" "$tag_a"; tags+=("$tag_a")

echo "▶ image on an empty database"
printf 'APP_IMAGE=%s\n' "$tag_a" > "$LIFEINUK_DIR/.env"
compose up -d db >/dev/null 2>&1
wait_healthy db 90
compose up -d app >/dev/null 2>&1
wait_healthy app 240
migrations=$(python3 "$repo/deploy/ci/check-migrations.py" --pending-after 0 --json | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
grep -q "Successfully applied $migrations migrations" <<<"$(compose logs app 2>&1)" && pass "Flyway applied all $migrations migrations to an empty database" || fail "Flyway did not apply $migrations migrations"
expect "Flyway history at the latest version" "$((next - 1))" "$(flyway_version life_in_uk)"
expect "tables owned by the application role" 0 "$(db_sql postgres life_in_uk "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'life_in_uk_app'")"
compose up -d proxy >/dev/null 2>&1
for _ in $(seq 1 30); do curl -sk -o /dev/null --resolve origin.test:18443:127.0.0.1 https://origin.test:18443/ && break; sleep 1; done

echo "▶ origin protection (nginx) and app"
origin=https://origin.test:18443
req() { curl -s --resolve origin.test:18443:127.0.0.1 --cacert "$LIFEINUK_CERTS/live/origin.test/fullchain.pem" "$@" || true; }
status() { req -o /dev/null -w '%{http_code}' "$@"; }
H=(-H "X-Origin-Verify: $secret")
expect "no secret header → 403" 403 "$(status "$origin/api/guides")"
expect "wrong secret → 403" 403 "$(status -H 'X-Origin-Verify: nope' "$origin/api/guides")"
expect "secret header → /api/guides 200" 200 "$(status "${H[@]}" "$origin/api/guides")"
expect "empty database → no Guides" "[]" "$(req "${H[@]}" "$origin/api/guides")"
expect "API gzip" gzip "$(req "${H[@]}" -H 'Accept-Encoding: gzip' -o /dev/null -w '%header{content-encoding}' "$origin/api/guides")"
expect "backend 404 keeps its own JSON body" GUIDE_NOT_FOUND "$(req "${H[@]}" "$origin/api/guides/no-such-guide" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("code"))' 2>/dev/null)"
expect "owner API blocked" 404 "$(status "${H[@]}" "$origin/api/owner/editorial/session")"
expect "editorial API blocked" 404 "$(status "${H[@]}" "$origin/api/editorial/guide-batches/x")"
expect "POST refused" 403 "$(status "${H[@]}" -X POST -d '{}' "$origin/api/guides")"
expect "actuator not exposed" 404 "$(status "${H[@]}" "$origin/actuator/health")"
expect "robots.txt names the public sitemap" "Sitemap: https://lifeinuk.info/sitemap.xml" "$(req "${H[@]}" "$origin/robots.txt" | grep '^Sitemap:')"
expect "editorial API not configured → 503" 503 "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/editorial/guide-batches/x)"
expect "database has no published port" "" "$(docker inspect -f '{{range $p, $b := .HostConfig.PortBindings}}{{$p}}{{end}}' "$(compose ps -q db)")"
expect "app published on loopback only" 127.0.0.1 "$(docker inspect -f '{{(index (index .HostConfig.PortBindings "8080/tcp") 0).HostIp}}' "$(compose ps -q app)")"
expect "app runs as uid 10001" 10001 "$(compose exec -T app id -u | tr -d '\r')"
expect "app root filesystem read-only" true "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$(compose ps -q app)")"

echo "▶ release.sh"
st=$("$release" status 2>/dev/null)
expect "status: current image" "$tag_a" "$(field current_image "$st")"
expect "status: deploy files digest equals the repository's" "$digest" "$(field deploy_files_digest "$st")"
expect "status: no rollback target yet" "" "$(field rollback_image "$st")"
refused "rollback without a recorded release" "no previous release" "$release" rollback false
refused "deploy refused when the server's deploy files differ" "differ from the release commit" "$release" deploy "$commit_b" "$sha_b" "$(printf '0%.0s' {1..64})" none
refused "deploy refused for a wrong archive checksum" "checksum differs" "$release" deploy "$commit_b" "$sha_c" "$digest" none
expect "still on the first image" "$tag_a" "$(current)"

db_sql postgres life_in_uk "CREATE TABLE ci_marker (note text); INSERT INTO ci_marker VALUES ('kept')"
out=$("$release" deploy "$commit_b" "$sha_b" "$digest" none 2>/dev/null) && pass "deploy B: $(grep '^RESULT' <<<"$out")" || fail "deploy B failed: $out"
expect "B running" "$tag_b" "$(current)"
expect "B healthy" healthy "$(field app_health "$("$release" status 2>/dev/null)")"
backups=("$LIFEINUK_BACKUPS"/*"pre-release-${commit_b:0:12}.dump")
expect "pre-release backup taken" 1 "$(compgen -G "${backups[0]}" | wc -l | tr -d ' ')"
out=$("$release" deploy "$commit_b" "$sha_b" "$digest" none 2>/dev/null) && grep -q '^RESULT unchanged' <<<"$out" && pass "redeploying the running release changes nothing" || fail "redeploy: $out"

refused "migration refused without approval" "pending for this database: $next; approved: none" "$release" deploy "$commit_c" "$sha_c" "$digest" none
expect "schema unchanged after the refusal" "$((next - 1))" "$(flyway_version life_in_uk)"
refused "migration refused when other versions are approved" "approved: 99" "$release" deploy "$commit_c" "$sha_c" "$digest" 99
out=$("$release" deploy "$commit_c" "$sha_c" "$digest" "$next" 2>/dev/null) && pass "deploy C with V$next approved" || fail "deploy C: $out"
expect "C running" "$tag_c" "$(current)"
expect "V$next applied" "$next" "$(flyway_version life_in_uk)"
db_sql postgres life_in_uk "INSERT INTO ci_release_test VALUES ('row written by C')"

refused "rollback across the newer schema needs explicit approval" "the database is at V$next" "$release" rollback false
out=$("$release" rollback true 2>/dev/null) && pass "rollback to B with V$next kept: $(grep '^RESULT' <<<"$out")" || fail "rollback: $out"
expect "B running again" "$tag_b" "$(current)"
expect "schema not rolled back" "$next" "$(flyway_version life_in_uk)"

out=$("$release" deploy "$commit_d" "$sha_d" "$digest" none 2>/dev/null) && fail "a broken release reported success" || pass "a broken release fails: $(grep '^RESULT' <<<"$out")"
expect "previous image back after the failed release" "$tag_b" "$(current)"
expect "previous image healthy" healthy "$(field app_health "$("$release" status 2>/dev/null)")"
docker image inspect "$tag_d" >/dev/null 2>&1 && fail "the failed image is still on disk" || pass "the failed image was removed"

out=$("$release" rollback false 2>/dev/null) && pass "rollback after a rollback rolls forward to C" || fail "roll forward: $out"
expect "C running" "$tag_c" "$(current)"

exec 8>"$RELEASE_LOCK"; flock 8
refused "a second release is refused while one runs" "another release is running" "$release" status
flock -u 8; exec 8>&-

echo "▶ SSM document and deploy/ci/ssm-release.sh (fake AWS CLI running the document's command)"
mkdir -p "$tmp/bin"; ln -s "$repo/deploy/test/fake-aws" "$tmp/bin/aws"
ssm() { PATH="$tmp/bin:$PATH" FAKE_AWS_STATE="$tmp/ssm" INSTANCE_ID=i-test SSM_DOCUMENT=LifeInUK-BackendRelease SSM_POLL_SECONDS=0 SSM_OUT="$tmp/ssm.out" \
  "$repo/deploy/ci/ssm-release.sh" "$@"; }
ssm status >/dev/null 2>&1 && expect "SSM status reports the running image" "$tag_c" "$(field current_image "$(cat "$tmp/ssm.out")")" || fail "SSM status failed"
refused "SSM refuses a parameter outside the document's pattern" "does not match" ssm deploy 'commit=$(touch /tmp/pwned)' archiveSha256="$sha_b" filesDigest="$digest"
refused "SSM refuses an unknown action" "not an allowed value" ssm reboot
refused "SSM refuses parameters the document does not have" "unknown parameters" ssm status script=id
out=$(ssm deploy commit="$commit_b" archiveSha256="$sha_b" filesDigest="$digest" approvedMigrations=none 2>&1) \
  && grep -q '^RESULT deployed image='"$tag_b" "$tmp/ssm.out" && pass "SSM deploy runs release.sh deploy" || fail "SSM deploy: $(tail -3 <<<"$out")"
out=$(ssm deploy commit="$commit_c" archiveSha256="$sha_c" filesDigest="$digest" approvedMigrations=99 2>&1) \
  && fail "SSM deploy reported success for a refused release" || pass "a refused release fails the SSM command"
expect "B still running after the refusal" "$tag_b" "$(current)"
out=$(ssm rollback allowNewerSchema=false 2>&1) && expect "SSM rollback" "$tag_c" "$(current)" || fail "SSM rollback: $(tail -3 <<<"$out")"

echo "▶ data, images and audit trail"
expect "rows written before the releases survive" kept "$(db_sql postgres life_in_uk "SELECT note FROM ci_marker")"
expect "rows written by a release survive" 1 "$(db_sql postgres life_in_uk "SELECT count(*) FROM ci_release_test")"
expect "Flyway history only grew (no repair or reset)" "$next" "$(db_sql postgres life_in_uk "SELECT count(*) FROM flyway_schema_history WHERE success")"
images=$(docker image ls --format '{{.Tag}}' "$IMAGE_REPO" | grep -vc '<none>' || true)
[ "$images" -le "$KEEP_IMAGES" ] && pass "old images pruned ($images left)" || fail "too many images kept: $images"
log_lines=$(grep -c -E ' (deploy|rollback) (start|ok|failed) ' "$LIFEINUK_DIR/releases.log")
[ "$log_lines" -ge 8 ] && pass "releases.log records every step ($log_lines lines)" || fail "releases.log has $log_lines lines"
grep -q " deploy failed image=$tag_d restored=$tag_b " "$LIFEINUK_DIR/releases.log" && pass "the failed release is in the audit trail" || fail "failed release not logged"

echo
echo "ci stack test: $passed passed, $failed failed"
[ "$failed" = 0 ]
