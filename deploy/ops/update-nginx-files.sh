#!/usr/bin/env bash
# Installs a reviewed commit's nginx template (and env/*.example reference
# copies) on the server, file by file, without a release and without touching
# a running container (docs/deploy-files-update.md, "nginx changes"). Run as
# root on the server, normally through an owner-approved SSM
# AWS-RunShellScript command.
#
#   update-nginx-files.sh install <deploy-files.tar.gz> <its sha256> <old digest> <new digest>
#   update-nginx-files.sh validate [<templates dir>]      (read-only; default: the installed templates)
#
# update-deploy-files.sh refuses nginx changes, and it replaces whole
# directories: the running proxy's bind mount of nginx/templates then points
# at the removed directory. This script instead:
#   1. Takes the release lock. Refuses unless the server digest is <old>, the
#      archive matches its sha256, holds only deploy files and unpacks to <new>.
#   2. Lists every file the archive would add or replace and every server file
#      it lacks. Refuses unless all changes are under nginx/ or env/*.example,
#      at least one is under nginx/, and nothing would have to be deleted.
#      compose.yml, db/, scripts/, systemd/ and certbot/ must be identical.
#   3. Validates the staged templates with `nginx -t` in a throwaway container
#      of the running proxy's image: no network, no published port, read-only
#      mounts, removed afterwards. The running proxy is not touched.
#   4. Records release.sh status and the containers, saves each file it will
#      replace to /root/lifeinuk-nginx-files-before-<time>/ (0700).
#   5. Installs each changed file in place (same directory, rename, root 0644).
#   6. Checks the new digest, the same image, Flyway version, rollback target
#      and healthy app, and unchanged containers (IDs, start times, restarts).
# Any failure after step 4 puts the saved files back, removes added files,
# checks the old digest and exits nonzero (RESULT restored …).
#
# The proxy keeps serving the configuration it rendered at its start until it
# is recreated (`docker compose up -d --force-recreate --no-deps proxy`, a
# separate approved step). Nothing here touches .env, releases.log, other
# directories under /opt/lifeinuk, /etc/lifeinuk, certificates or systemd.
set -euo pipefail
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin:$PATH"
LIFEINUK_DIR=${LIFEINUK_DIR:-/opt/lifeinuk}
LIFEINUK_ETC=${LIFEINUK_ETC:-/etc/lifeinuk}
LIFEINUK_CERTS=${LIFEINUK_CERTS:-/etc/letsencrypt}
SAVE_ROOT=${SAVE_ROOT:-/root}
RELEASE_LOCK=${RELEASE_LOCK:-/run/lock/lifeinuk-release.lock}
LIFEINUK_PROJECT=${LIFEINUK_PROJECT:-lifeinuk}
export LIFEINUK_DIR RELEASE_LOCK LIFEINUK_PROJECT
parts=(compose.yml nginx db scripts systemd env certbot)

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; printf 'RESULT refused %s\n' "$*"; exit 1; }
# Same as deploy_files_digest in deploy/scripts/common.sh.
digest() {
  (cd "$1" && find compose.yml nginx db scripts systemd -type f ! -name '*.pyc' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) \
    | sha256sum | cut -c1-64
}
field() { sed -n "s/^$1=//p" <<<"$2"; }
containers() {
  docker ps -q --filter "label=com.docker.compose.project=$LIFEINUK_PROJECT" | sort \
    | xargs -r docker inspect -f '{{.Name}} {{.Id}} {{.State.StartedAt}} {{.RestartCount}}' | sort
}
# Every file of the deploy parts, relative, sorted.
files_of() {
  (cd "$1" && for p in "${parts[@]}"; do [ ! -e "$p" ] || find "$p" -type f ! -name '*.pyc'; done) | LC_ALL=C sort
}

# nginx -t on a templates directory, in a throwaway container of the running proxy's image with
# the proxy's environment file and certificates, read-only, without network or ports.
validate() {
  local templates=$1 proxy image out
  [ -d "$templates" ] || die "no templates directory $templates"
  proxy=$(docker ps -q --filter "label=com.docker.compose.project=$LIFEINUK_PROJECT" --filter label=com.docker.compose.service=proxy | head -1)
  [ -n "$proxy" ] || die "no running proxy container in project $LIFEINUK_PROJECT"
  image=$(docker inspect -f '{{.Config.Image}}' "$proxy")
  if out=$(docker run --rm --pull never --network none --read-only --memory 128m \
        --tmpfs /etc/nginx/conf.d --tmpfs /var/cache/nginx --tmpfs /run --tmpfs /tmp \
        --env-file "$LIFEINUK_ETC/proxy.env" -e NGINX_ENVSUBST_OUTPUT_DIR=/etc/nginx/conf.d -e 'NGINX_ENVSUBST_FILTER=^ORIGIN_' \
        -v "$templates:/etc/nginx/templates:ro" -v "$LIFEINUK_CERTS:/etc/letsencrypt:ro" \
        "$image" nginx -t 2>&1); then
    log "nginx -t passed for $templates ($image)"
  else
    printf '%s\n' "$out" | grep -E 'emerg|warn|failed' >&2 || true
    return 1
  fi
}

action=${1:-}
case "$action" in
  validate)
    validate "${2:-$LIFEINUK_DIR/nginx/templates}" || die "nginx -t failed"
    echo "RESULT valid"; exit 0 ;;
  install) shift ;;
  *) die "usage: update-nginx-files.sh install <tar.gz> <sha256> <old digest> <new digest> | validate [<templates dir>]" ;;
esac

tarball=${1:-} tar_sha=${2:-} old=${3:-} new=${4:-}
[ -f "$tarball" ] || die "usage: update-nginx-files.sh install <tar.gz> <sha256> <old digest> <new digest>"
for v in "$tar_sha" "$old" "$new"; do [[ "$v" =~ ^[0-9a-f]{64}$ ]] || die "checksums and digests must be 64 hex characters"; done

# 1. Lock and checks; nothing is changed yet.
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

# 2. File-level plan.
changes=() missing=()
while IFS= read -r f; do
  if [ ! -e "$LIFEINUK_DIR/$f" ]; then changes+=("add $f")
  elif ! cmp -s "$stage/$f" "$LIFEINUK_DIR/$f"; then changes+=("replace $f"); fi
done < <(files_of "$stage")
while IFS= read -r f; do [ -e "$stage/$f" ] || missing+=("$f"); done < <(files_of "$LIFEINUK_DIR")
for c in "${changes[@]}"; do log "plan: $c"; done
for m in "${missing[@]}"; do log "plan: server-only $m"; done
[ "${#missing[@]}" = 0 ] || die "the archive lacks server files (${missing[*]}); this procedure never deletes"
[ "${#changes[@]}" -gt 0 ] || die "nothing to install"
nginx_changed=0
for c in "${changes[@]}"; do
  f=${c#* }
  case "$f" in
    nginx/*) nginx_changed=1 ;;
    env/*.example) ;;
    *) die "$f changes; only nginx/ and env/*.example are installed here (use update-deploy-files.sh)" ;;
  esac
done
[ "$nginx_changed" = 1 ] || die "no nginx change; use update-deploy-files.sh"

# 3. Validate the staged templates; the running proxy is not touched.
validate "$stage/nginx/templates" || die "nginx -t failed for the staged templates; nothing changed"

# 4. Record and save.
status() { RELEASE_LOCK="$stage/status.lock" "${RELEASE_SH:-$LIFEINUK_DIR/scripts/release.sh}" status 2>/dev/null; }
before_status=$(status) || die "release.sh status failed"
[ "$(field app_health "$before_status")" = healthy ] || die "app is not healthy; not changing anything"
before_containers=$(containers)
save="$SAVE_ROOT/lifeinuk-nginx-files-before-$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0700 "$save"
for c in "${changes[@]}"; do
  f=${c#* }
  if [ "${c%% *}" = replace ]; then install -D -m 0600 "$LIFEINUK_DIR/$f" "$save/$f"; echo "replace $f $(stat -c %a "$LIFEINUK_DIR/$f")"; else echo "add $f"; fi
done > "$save/MANIFEST"
log "saved the files to be replaced in $save"

committed=0
restore() {
  [ "$committed" = 1 ] && { rm -rf "$stage"; return; }
  log "restoring from $save"
  while read -r kind f mode; do
    if [ "$kind" = replace ]; then install -m "$mode" -o "$(id -u)" -g "$(id -g)" "$save/$f" "$LIFEINUK_DIR/$f"; else rm -f "${LIFEINUK_DIR:?}/$f"; fi
  done < "$save/MANIFEST"
  if [ "$(digest "$LIFEINUK_DIR")" = "$old" ]; then
    printf 'RESULT restored digest=%s saved=%s\n' "$old" "$save"
  else
    printf 'RESULT ERROR restore did not reproduce %s; files are in %s\n' "$old" "$save"
  fi
  rm -rf "$stage"
}
trap restore EXIT
fail() { log "ERROR: $*"; exit 1; }

# 5. Install file by file: a temporary file in the same directory, then rename.
for c in "${changes[@]}"; do
  f=${c#* }
  install -d -m 0755 "$(dirname "$LIFEINUK_DIR/$f")"
  tmp=$(mktemp "$(dirname "$LIFEINUK_DIR/$f")/.update.XXXXXX")
  cat "$stage/$f" > "$tmp"
  chmod 0644 "$tmp"
  chown root:root "$tmp" 2>/dev/null || [ -n "${UPDATE_TEST:-}" ] || fail "chown $f"
  mv "$tmp" "$LIFEINUK_DIR/$f"
  log "installed $f"
done
[ -z "${UPDATE_TEST_FAIL_AFTER_INSTALL:-}" ] || fail "test: simulated failure after install"

# 6. Verify.
[ "$(digest "$LIFEINUK_DIR")" = "$new" ] || fail "installed digest is not $new"
for c in "${changes[@]}"; do cmp -s "$stage/${c#* }" "$LIFEINUK_DIR/${c#* }" || fail "${c#* } differs after install"; done
after_status=$(status) || fail "release.sh status failed after install"
for key in current_image current_image_id flyway_version rollback_image app_health; do
  [ "$(field "$key" "$after_status")" = "$(field "$key" "$before_status")" ] || fail "$key changed: $(field "$key" "$before_status") -> $(field "$key" "$after_status")"
done
[ "$(field deploy_files_digest "$after_status")" = "$new" ] || fail "release.sh reports another digest"
[ "$(containers)" = "$before_containers" ] || fail "containers changed"
committed=1
printf 'RESULT updated digest=%s->%s files=%s image=%s flyway=%s saved=%s (the proxy serves the old configuration until it is recreated)\n' \
  "$old" "$new" "$(IFS=,; echo "${changes[*]// /:}")" "$(field current_image "$after_status")" "$(field flyway_version "$after_status")" "$save"
