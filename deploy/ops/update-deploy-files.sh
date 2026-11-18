#!/usr/bin/env bash
# Replaces the server's deployment files with those of a reviewed commit,
# without a release (docs/deploy-files-update.md). Run as root on the server,
# normally through an owner-approved SSM AWS-RunShellScript command.
#
#   update-deploy-files.sh <deploy-files.tar.gz> <its sha256> <old digest> <new digest> [<BACKUP_S3_URI>]
#
# The archive holds compose.yml nginx db scripts systemd env certbot from
# deploy/ of the commit (deploy/ops/stage-deploy-files.sh makes it). Steps:
#   1. Takes the release lock, so no release runs meanwhile.
#   2. Refuses unless the server's deploy-files digest is <old digest>, the
#      archive matches its sha256 and unpacks to <new digest>, and
#      compose.yml, nginx and db are unchanged (this procedure never restarts
#      a container; changes there need a planned `docker compose up`).
#   3. Records the running state (release.sh status, container IDs and start
#      times) and copies the current files and backup.env to
#      /root/lifeinuk-deploy-files-before-<time>.
#   4. Installs the new files. With <BACKUP_S3_URI>, also writes it to
#      backup.env, after checking it equals the nightly unit's.
#   5. Checks the new digest, the same image, Flyway version and healthy
#      app, unchanged containers, and the backup location.
# Any failure after step 3 puts the saved files back, checks the old digest
# again and exits nonzero. It never runs a release, restarts or recreates a
# container, touches the database, the other env files or systemd units.
set -euo pipefail
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin:$PATH"
LIFEINUK_DIR=${LIFEINUK_DIR:-/opt/lifeinuk}
BACKUP_ENV=${BACKUP_ENV:-/etc/lifeinuk/backup.env}
SAVE_ROOT=${SAVE_ROOT:-/root}
RELEASE_LOCK=${RELEASE_LOCK:-/run/lock/lifeinuk-release.lock}
LIFEINUK_PROJECT=${LIFEINUK_PROJECT:-lifeinuk}
export LIFEINUK_DIR BACKUP_ENV RELEASE_LOCK LIFEINUK_PROJECT
parts=(compose.yml nginx db scripts systemd env certbot)

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; printf 'RESULT refused %s\n' "$*"; exit 1; }
# Same as deploy_files_digest in deploy/scripts/common.sh (not sourced: that file is being replaced).
digest() {
  (cd "$1" && find compose.yml nginx db scripts systemd -type f ! -name '*.pyc' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) \
    | sha256sum | cut -c1-64
}
field() { sed -n "s/^$1=//p" <<<"$2"; }
# release.sh status of the installed scripts; its own lock file, as this script holds the release lock.
status() { RELEASE_LOCK="$stage/status.lock" "$LIFEINUK_DIR/scripts/release.sh" status 2>/dev/null; }
containers() {
  docker ps -q --filter "label=com.docker.compose.project=$LIFEINUK_PROJECT" | sort \
    | xargs -r docker inspect -f '{{.Name}} {{.Id}} {{.State.StartedAt}} {{.RestartCount}}' | sort
}
# BACKUP_S3_URI as the nightly unit sees it (its Environment= or EnvironmentFile), normalised.
nightly_uri() {
  local env file
  env=$(systemctl show -p Environment --value lifeinuk-backup.service | tr ' ' '\n' | sed -n 's/^BACKUP_S3_URI=//p' | tail -1)
  if [ -z "$env" ]; then
    file=$(sed -n 's/^BACKUP_S3_URI=//p' "$BACKUP_ENV" 2>/dev/null | tail -1); env=${file//[\"\']/}
  fi
  [ -z "$env" ] || printf '%s/' "${env%/}"
}

tarball=${1:-} tar_sha=${2:-} old=${3:-} new=${4:-} s3_uri=${5:-}
[ -f "$tarball" ] || die "usage: update-deploy-files.sh <tar.gz> <sha256> <old digest> <new digest> [<BACKUP_S3_URI>]"
for v in "$tar_sha" "$old" "$new"; do [[ "$v" =~ ^[0-9a-f]{64}$ ]] || die "checksums and digests must be 64 hex characters"; done
[ -z "$s3_uri" ] || { s3_uri=${s3_uri%/}/; [[ "$s3_uri" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/([A-Za-z0-9._=-]+/)+$ ]] || die "malformed BACKUP_S3_URI"; }

# 1–2. Lock and checks; nothing is changed yet.
exec 9>"$RELEASE_LOCK"
flock -n 9 || die "a release is running"
[ "$(id -u)" = 0 ] || [ -n "${UPDATE_TEST:-}" ] || die "run as root"
current=$(digest "$LIFEINUK_DIR")
[ "$current" = "$old" ] || die "server deploy-files digest is $current, expected $old"
[ "$(sha256sum "$tarball" | cut -c1-64)" = "$tar_sha" ] || die "archive checksum differs"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
tar -tzf "$tarball" | grep -v -E '^(compose\.yml|(nginx|db|scripts|systemd|env|certbot)(/[A-Za-z0-9._/-]*)?)$' | grep -q . \
  && die "archive holds paths other than ${parts[*]}"
tar -xzf "$tarball" -C "$stage" --no-same-owner --no-same-permissions
[ "$(digest "$stage")" = "$new" ] || die "archive digest is $(digest "$stage"), expected $new"
for p in compose.yml nginx db; do
  diff -rq "$LIFEINUK_DIR/$p" "$stage/$p" >/dev/null || die "$p differs; this procedure never restarts containers"
done
if [ -n "$s3_uri" ]; then
  nightly=$(nightly_uri)
  [ "$nightly" = "$s3_uri" ] || die "BACKUP_S3_URI $s3_uri differs from the nightly unit's (${nightly:-none})"
fi
cli=$(aws --version 2>&1 | sed -n 's#^aws-cli/\([0-9]*\)\.\([0-9]*\)\..*#\1 \2#p')
[ -n "$cli" ] && { read -r major minor <<<"$cli"; [ "$major" -gt 2 ] || { [ "$major" = 2 ] && [ "$minor" -ge 9 ]; }; } \
  || die "AWS CLI v2.9+ needed for checksummed uploads (have: $(aws --version 2>&1 | head -1))"
before_status=$(status) || die "release.sh status failed"
before_containers=$(containers)
[ "$(field app_health "$before_status")" = healthy ] || die "app is not healthy; not changing anything"

# 3. Save the current files.
save="$SAVE_ROOT/lifeinuk-deploy-files-before-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$save"
for p in "${parts[@]}"; do [ ! -e "$LIFEINUK_DIR/$p" ] || cp -a "$LIFEINUK_DIR/$p" "$save/"; done
[ ! -f "$BACKUP_ENV" ] || cp -a "$BACKUP_ENV" "$save/backup.env"
[ "$(digest "$save")" = "$old" ] || die "saved copy does not match the old digest"
log "saved the current files in $save"

committed=0
restore() {
  [ "$committed" = 1 ] && { rm -rf "$stage"; return; }
  log "restoring the saved files from $save"
  for p in "${parts[@]}"; do
    rm -rf "${LIFEINUK_DIR:?}/$p"
    [ ! -e "$save/$p" ] || cp -a "$save/$p" "$LIFEINUK_DIR/"
  done
  if [ -f "$save/backup.env" ]; then cp -a "$save/backup.env" "$BACKUP_ENV"; fi
  if [ "$(digest "$LIFEINUK_DIR")" = "$old" ]; then
    printf 'RESULT restored digest=%s saved=%s\n' "$old" "$save"
  else
    printf 'RESULT ERROR restore did not reproduce %s; files are in %s\n' "$old" "$save"
  fi
  rm -rf "$stage"
}
trap restore EXIT
fail() { log "ERROR: $*"; exit 1; }

# 4. Install.
for p in "${parts[@]}"; do
  [ -e "$stage/$p" ] || continue
  rm -rf "${LIFEINUK_DIR:?}/.$p.new"
  cp -a "$stage/$p" "$LIFEINUK_DIR/.$p.new"
  chown -R root:root "$LIFEINUK_DIR/.$p.new" 2>/dev/null || [ -n "${UPDATE_TEST:-}" ] || fail "chown $p"
  rm -rf "${LIFEINUK_DIR:?}/$p"
  mv "$LIFEINUK_DIR/.$p.new" "$LIFEINUK_DIR/$p"
done
[ -z "${UPDATE_TEST_FAIL_AFTER_INSTALL:-}" ] || fail "test: simulated failure after install"
if [ -n "$s3_uri" ]; then
  tmp_env=$(mktemp "$(dirname "$BACKUP_ENV")/.backup.env.XXXX")
  { grep -v '^BACKUP_S3_URI=' "$BACKUP_ENV" 2>/dev/null || true; printf 'BACKUP_S3_URI=%s\n' "$s3_uri"; } > "$tmp_env"
  chmod 0600 "$tmp_env" && mv "$tmp_env" "$BACKUP_ENV"
fi

# 5. Verify.
[ "$(digest "$LIFEINUK_DIR")" = "$new" ] || fail "installed digest is not $new"
after_status=$(status) || fail "new release.sh status failed"
for key in current_image current_image_id flyway_version rollback_image app_health; do
  [ "$(field "$key" "$after_status")" = "$(field "$key" "$before_status")" ] || fail "$key changed: $(field "$key" "$before_status") -> $(field "$key" "$after_status")"
done
[ "$(field deploy_files_digest "$after_status")" = "$new" ] || fail "release.sh reports another digest"
if [ -n "$s3_uri" ]; then
  [ "$(field backup_s3_uri "$after_status")" = "$s3_uri" ] || fail "release.sh does not see BACKUP_S3_URI $s3_uri"
fi
[ "$(containers)" = "$before_containers" ] || fail "containers changed"
committed=1
printf 'RESULT updated digest=%s->%s image=%s flyway=%s backup_s3_uri=%s saved=%s\n' "$old" "$new" \
  "$(field current_image "$after_status")" "$(field flyway_version "$after_status")" "$(field backup_s3_uri "$after_status")" "$save"
