# shellcheck shell=bash
# Shared helpers for the operator scripts in deploy/scripts (sourced, not run).
# Production layout: /opt/lifeinuk (compose.yml, scripts, nginx, db),
# /etc/lifeinuk (env files), /var/backups/lifeinuk (dumps; mounted at /backups
# in the db container). Every path can be overridden for the local stack test.
LIFEINUK_DIR=${LIFEINUK_DIR:-/opt/lifeinuk}
LIFEINUK_BACKUPS=${LIFEINUK_BACKUPS:-/var/backups/lifeinuk}
export LIFEINUK_BACKUPS

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

compose() {
  docker compose --project-directory "$LIFEINUK_DIR" -f "$LIFEINUK_DIR/compose.yml" ${LIFEINUK_PROJECT:+-p "$LIFEINUK_PROJECT"} "$@"
}

# psql inside the db container over the local socket (no password; the
# container is only reachable by root on the host). Usage: db_sql <role> <database> <sql>
db_sql() {
  compose exec -T db psql -X -v ON_ERROR_STOP=1 -q -At -U "$1" -d "$2" -c "$3"
}

running() { [ -n "$(compose ps --status running -q "$1" 2>/dev/null)" ]; }

wait_healthy() {
  local service=$1 seconds=${2:-180} id state
  for _ in $(seq 1 "$seconds"); do
    id=$(compose ps -q "$service")
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)
    [ "$state" = healthy ] && return 0
    sleep 1
  done
  die "$service is not healthy after ${seconds}s (last state: ${state:-unknown})"
}

# A dump must live in the backup directory, so the db container sees it as /backups/<name>.
container_path() {
  local file=$1
  [ -f "$file" ] || die "$file not found"
  [ "$(cd "$(dirname "$file")" && pwd -P)" = "$(cd "$LIFEINUK_BACKUPS" && pwd -P)" ] \
    || die "$file must be in $LIFEINUK_BACKUPS (mounted at /backups in the db container)"
  printf '/backups/%s' "$(basename "$file")"
}

verify_checksum() {
  local file=$1
  if [ -f "$file.sha256" ]; then
    (cd "$(dirname "$file")" && sha256sum --quiet -c "$(basename "$file").sha256") || die "checksum mismatch for $file"
    log "checksum OK: $(basename "$file")"
  else
    return 1
  fi
}

# Digest of the deployment files that the server runs from (compose.yml, nginx,
# db init, scripts, systemd), over relative paths and contents. CI computes it
# for deploy/ of the release commit and release.sh for /opt/lifeinuk: a
# difference means the server copy is out of date (RUNBOOK.md section 3).
deploy_files_digest() {
  (cd "$1" && find compose.yml nginx db scripts systemd -type f ! -name '*.pyc' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) \
    | sha256sum | cut -c1-64
}

# Highest successfully applied Flyway version of a database (0 if none).
flyway_version() {
  db_sql postgres "$1" "SELECT coalesce(max(version::int), 0) FROM flyway_schema_history WHERE success AND version IS NOT NULL"
}

# One line describing the published content of a database.
content_summary() {
  db_sql postgres "$1" "SELECT 'guides=' || (SELECT count(*) FROM guide WHERE status = 'PUBLISHED')
    || ' localized=' || (SELECT count(*) FROM guide_locale_current)
    || ' open_publications=' || (SELECT count(*) FROM guide_publication WHERE ended_at IS NULL)
    || ' bank_holiday_artifacts=' || (SELECT count(*) FROM evidence_artifact a JOIN ingestion_run r ON r.id = a.ingestion_run_id
         JOIN source_endpoint e ON e.id = r.source_endpoint_id WHERE e.endpoint_key = 'gov-uk-bank-holidays-json')
    || ' flyway=V' || (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL)"
}
