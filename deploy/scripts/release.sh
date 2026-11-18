#!/usr/bin/env bash
# Backend application release on the server (docs/ci-cd.md, RUNBOOK.md section 3).
# Run as root by the SSM document LifeInUK-BackendRelease (GitHub Actions), or
# by hand. Changes only the app container's image; never the database content,
# the volumes, the env files or the other services.
#
#   release.sh status
#       key=value lines: current image, Flyway version, deploy-files digest,
#       rollback target, app health.
#
#   release.sh deploy <commit> <archive sha256> <deploy-files digest> <approved migrations|none>
#       1. Refuses unless the server's deployment files match the release
#          commit's (digest), and the archive's SHA-256 is the one CI built.
#       2. Loads lifeinuk-backend:<commit12> from $RELEASE_SOURCE/<commit>/ and
#          checks its revision label.
#       3. Refuses if the image brings Flyway migrations newer than the
#          database unless exactly those versions are approved ("16,17").
#       4. Takes a backup (backup.sh pre-release-<commit12>) and its copy in
#          S3 (BACKUP_S3_URI from /etc/lifeinuk/backup.env, SHA-256 verified
#          by S3; refuses before any change when that is not configured, and
#          stops with nothing changed when the backup or its copy fails), then
#          switches APP_IMAGE, starts the app, waits for health and checks the API.
#       5. On failure puts the previous image back (the database is never
#          restored automatically) and exits nonzero.
#
#   release.sh rollback <allow newer schema: true|false>
#       Back to the image recorded as "previous" by the last release or
#       rollback. Refuses if the database has migrations the old image does not
#       know, unless allowed (Flyway ignores future migrations; the old code
#       must still work with the newer schema: see docs/ci-cd.md).
#
# Everything is appended to $LIFEINUK_DIR/releases.log (audit trail).
# Every path can be overridden for the local tests (deploy/test/ci-stack-test.sh).
set -euo pipefail
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin:$PATH"
here=$(dirname "$(readlink -f "$0")")
source "$here/common.sh"

RELEASE_ENV=${RELEASE_ENV:-/etc/lifeinuk/release.env}
# shellcheck source=/dev/null
[ ! -f "$RELEASE_ENV" ] || source "$RELEASE_ENV"
RELEASE_SOURCE=${RELEASE_SOURCE:-}          # s3://bucket/prefix or a local directory (tests)
RELEASE_WORK=${RELEASE_WORK:-/var/lib/lifeinuk/releases}
RELEASE_LOCK=${RELEASE_LOCK:-/run/lock/lifeinuk-release.lock}
RELEASE_HEALTH_SECONDS=${RELEASE_HEALTH_SECONDS:-300}
KEEP_IMAGES=${KEEP_IMAGES:-3}
IMAGE_REPO=${IMAGE_REPO:-lifeinuk-backend}   # tests use their own repository name
env_file="$LIFEINUK_DIR/.env"
history="$LIFEINUK_DIR/releases.log"

record() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >> "$history"; log "$*"; }
result() { printf 'RESULT %s\n' "$*"; }

current_image() { sed -n 's/^APP_IMAGE=//p' "$env_file" | tail -1; }
set_image() {
  local tmp="$env_file.tmp"
  { grep -v '^APP_IMAGE=' "$env_file" 2>/dev/null || true; printf 'APP_IMAGE=%s\n' "$1"; } > "$tmp"
  chmod 0644 "$tmp" && mv "$tmp" "$env_file"
}
# The rollback target: "previous=" of the last successful release or rollback.
previous_image() { [ -f "$history" ] && sed -n 's/.* \(deploy\|rollback\) ok .*previous=\([^ ]*\).*/\2/p' "$history" | tail -1 || true; }

# Flyway versions packaged in an image's jar (BOOT-INF/classes/db/migration/V<n>__*.sql), highest last.
image_migrations() {
  local id jar
  id=$(docker create "$1")
  jar=$(mktemp)
  docker cp -q "$id:/app/app.jar" "$jar" >/dev/null && docker rm "$id" >/dev/null
  python3 - "$jar" <<'PY'
import re, sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
found = sorted(int(m.group(1)) for n in names for m in [re.match(r"BOOT-INF/classes/db/migration/V([0-9]+)__", n)] if m)
print(" ".join(map(str, found)))
PY
  rm -f "$jar"
}

app_health() {
  local id
  id=$(compose ps -q app 2>/dev/null || true)
  [ -n "$id" ] || { echo missing; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo missing
}

# Healthy within RELEASE_HEALTH_SECONDS, failing fast when the container keeps restarting.
wait_app() {
  local id state restarts
  for _ in $(seq 1 "$RELEASE_HEALTH_SECONDS"); do
    id=$(compose ps -aq app 2>/dev/null || true)
    if [ -n "$id" ]; then
      state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)
      restarts=$(docker inspect -f '{{.RestartCount}}' "$id" 2>/dev/null || echo 0)
      [ "$state" = healthy ] && return 0
      [ "$restarts" -lt 3 ] || { log "app restarted $restarts times"; return 1; }
    fi
    sleep 1
  done
  log "app not healthy after ${RELEASE_HEALTH_SECONDS}s (last state: ${state:-unknown})"
  return 1
}

# The public API answers from the app (through the loopback port, as operators use it).
smoke() {
  local port=${LIFEINUK_APP_BIND:-127.0.0.1:8080} body
  body=$(curl -fsS --max-time 10 "http://$port/api/guides") || { log "smoke: /api/guides failed"; return 1; }
  python3 -c 'import json,sys; d=json.loads(sys.argv[1]); sys.exit(0 if isinstance(d, list) else 1)' "$body" \
    || { log "smoke: /api/guides is not a JSON list"; return 1; }
  curl -fsS --max-time 10 -o /dev/null "http://$port/sitemap.xml" || { log "smoke: /sitemap.xml failed"; return 1; }
  curl -fsS --max-time 10 -o /dev/null "http://$port/robots.txt" || { log "smoke: /robots.txt failed"; return 1; }
  printf '%s' "$(python3 -c 'import json,sys; print(len(json.loads(sys.argv[1])))' "$body")"
}

# Switches to $1 and checks it; on failure switches back to $2. Prints the Guide count.
switch_to() {
  local target=$1 fallback=$2 guides
  set_image "$target"
  compose up -d --no-deps app >/dev/null 2>&1 || true
  if wait_app && guides=$(smoke); then printf '%s' "$guides"; return 0; fi
  compose logs --no-color --tail 40 app >&2 || true
  if [ -n "$fallback" ]; then
    log "$target failed; putting $fallback back"
    set_image "$fallback"
    compose up -d --no-deps app >/dev/null 2>&1 || true
    if wait_app && smoke >/dev/null; then log "$fallback is healthy again"; else log "ERROR: $fallback is not healthy either: follow RUNBOOK.md section 3 (restore)"; fi
  fi
  return 1
}

status() {
  local image
  image=$(current_image)
  echo "current_image=$image"
  echo "current_image_id=$(docker image inspect -f '{{.Id}}' "$image" 2>/dev/null || echo unknown)"
  echo "flyway_version=$(flyway_version life_in_uk)"
  echo "deploy_files_digest=$(deploy_files_digest "$LIFEINUK_DIR")"
  echo "rollback_image=$(previous_image)"
  echo "app_health=$(app_health)"
  local s3
  if s3=$(backup_s3_uri 2>/dev/null); then echo "backup_s3_uri=${s3:-missing}"; else echo "backup_s3_uri=invalid"; fi
}

fetch_release() {
  local commit=$1 sha=$2 name dir
  name="$IMAGE_REPO-${commit:0:12}.tar.gz"
  dir="$RELEASE_WORK/$commit"
  rm -rf "$dir" && install -d -m 0700 "$dir"
  case "$RELEASE_SOURCE" in
    s3://*) aws s3 cp --only-show-errors "${RELEASE_SOURCE%/}/$commit/$name" "$dir/$name" ;;
    /*) cp "$RELEASE_SOURCE/$commit/$name" "$dir/$name" ;;
    *) die "RELEASE_SOURCE is not set ($RELEASE_ENV)" ;;
  esac
  [ "$(sha256sum "$dir/$name" | cut -c1-64)" = "$sha" ] || { rm -rf "$dir"; die "archive checksum differs from the one CI built"; }
  gunzip -c "$dir/$name" | docker load -q >/dev/null
  rm -rf "$dir"
}

prune_images() {
  local keep_current=$1 keep_previous=$2
  docker image ls --format '{{.CreatedAt}}\t{{.Repository}}:{{.Tag}}' "$IMAGE_REPO" | sort -r | cut -f2 \
    | grep -v -x -e "$keep_current" -e "${keep_previous:-none}" | tail -n +"$((KEEP_IMAGES - 1))" \
    | xargs -r docker image rm >/dev/null 2>&1 || true
}

deploy() {
  local commit=$1 sha=$2 digest=$3 approved=$4
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "commit must be 40 hex characters"
  [[ "$sha" =~ ^[0-9a-f]{64}$ ]] || die "archive sha256 must be 64 hex characters"
  [[ "$approved" =~ ^(none|[0-9]+(,[0-9]+)*)$ ]] || die "approved migrations must be none or a list like 16,17"
  local tag="$IMAGE_REPO:${commit:0:12}" current flyway_before files pending migrations guides backup_s3
  current=$(current_image)
  files=$(deploy_files_digest "$LIFEINUK_DIR")
  [ "$files" = "$digest" ] || die "deployment files on the server ($files) differ from the release commit's ($digest): sync deploy/ first (RUNBOOK.md section 3)"
  backup_s3=$(backup_s3_uri)
  [ -n "$backup_s3" ] || die "no off-instance location for the pre-release backup: set BACKUP_S3_URI in $BACKUP_ENV (RUNBOOK.md section 4); nothing changed"
  running db || die "the db service is not running"
  if [ "$current" = "$tag" ] && [ "$(app_health)" = healthy ]; then
    result "unchanged image=$tag (already running and healthy)"; return 0
  fi

  if ! docker image inspect "$tag" >/dev/null 2>&1; then fetch_release "$commit" "$sha"; fi
  [ "$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$tag")" = "$commit" ] \
    || die "$tag does not carry revision $commit"

  flyway_before=$(flyway_version life_in_uk)
  migrations=$(image_migrations "$tag")
  pending=$(tr ' ' '\n' <<<"$migrations" | awk -v v="$flyway_before" 'NF && $1 > v' | paste -sd, -)
  [ -n "$pending" ] || pending=none
  [ "$pending" = "$approved" ] || die "migrations pending for this database: $pending; approved: $approved. Review them and approve exactly these versions (docs/ci-cd.md)"

  backup=$(BACKUP_S3_URI="$backup_s3" BACKUP_S3_REQUIRED=1 BACKUP_RELEASE_COMMIT="$commit" "$here/backup.sh" "pre-release-${commit:0:12}") \
    || die "pre-release backup or its verified copy in S3 failed; nothing changed"
  record "deploy start image=$tag previous=$current flyway_before=$flyway_before pending=$pending backup=$(basename "$backup") backup_s3=$backup_s3$(basename "$backup")"
  if guides=$(switch_to "$tag" "$current"); then
    record "deploy ok image=$tag previous=$current image_id=$(docker image inspect -f '{{.Id}}' "$tag") flyway_before=$flyway_before flyway_after=$(flyway_version life_in_uk) guides=$guides"
    prune_images "$tag" "$current"
    result "deployed image=$tag image_id=$(docker image inspect -f '{{.Id}}' "$tag") previous=$current flyway=$flyway_before->$(flyway_version life_in_uk) guides=$guides backup=$(basename "$backup")"
  else
    record "deploy failed image=$tag restored=$current flyway_before=$flyway_before flyway_after=$(flyway_version life_in_uk)"
    [ "$(current_image)" = "$tag" ] || docker image rm "$tag" >/dev/null 2>&1 || true
    result "failed image=$tag running=$(current_image) flyway=$flyway_before->$(flyway_version life_in_uk) backup=$(basename "$backup")"
    return 1
  fi
}

rollback() {
  local allow=$1 current target flyway known guides
  [[ "$allow" =~ ^(true|false)$ ]] || die "allow newer schema must be true or false"
  current=$(current_image)
  target=$(previous_image)
  [ -n "$target" ] || die "no previous release recorded in $history; set APP_IMAGE by hand (RUNBOOK.md section 3)"
  [ "$target" != "$current" ] || die "$target is already running"
  docker image inspect "$target" >/dev/null 2>&1 || die "$target is no longer on this server"
  flyway=$(flyway_version life_in_uk)
  known=$(image_migrations "$target" | awk '{print $NF}')
  if [ "$flyway" -gt "${known:-0}" ] && [ "$allow" != true ]; then
    die "the database is at V$flyway but $target knows up to V${known:-0}; check that the old code works with the newer schema, then allow it (the database is never rolled back automatically)"
  fi
  record "rollback start image=$target previous=$current flyway=$flyway"
  if guides=$(switch_to "$target" "$current"); then
    record "rollback ok image=$target previous=$current flyway=$flyway guides=$guides"
    result "rolled back image=$target previous=$current flyway=V$flyway guides=$guides"
  else
    record "rollback failed image=$target restored=$current"
    result "failed rollback to $target; running=$(current_image)"
    return 1
  fi
}

action=${1:-}
case "$action" in
  status|deploy|rollback) ;;
  *) die "usage: release.sh status | deploy <commit> <archive sha256> <files digest> <approved migrations|none> | rollback <true|false>" ;;
esac
[ -f "$env_file" ] || die "$env_file not found"
exec 9>"$RELEASE_LOCK"
flock -n 9 || die "another release is running"
case "$action" in
  status) status ;;
  deploy) deploy "${2:-}" "${3:-}" "${4:-}" "${5:-none}" ;;
  rollback) rollback "${2:-false}" ;;
esac
