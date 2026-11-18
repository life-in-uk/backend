# shellcheck shell=bash
# Shared helpers for the operator scripts in deploy/scripts (sourced, not run).
# Production layout: /opt/lifeinuk (compose.yml, scripts, nginx, db),
# /etc/lifeinuk (env files), /var/backups/lifeinuk (dumps; mounted at /backups
# in the db container). Every path can be overridden for the local stack test.
LIFEINUK_DIR=${LIFEINUK_DIR:-/opt/lifeinuk}
LIFEINUK_BACKUPS=${LIFEINUK_BACKUPS:-/var/backups/lifeinuk}
BACKUP_ENV=${BACKUP_ENV:-/etc/lifeinuk/backup.env}
export LIFEINUK_BACKUPS BACKUP_ENV

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

# The off-instance backup location, normalised to end in "/": BACKUP_S3_URI
# from the environment (the nightly unit may set it) or from $BACKUP_ENV (read,
# not sourced). Empty when not configured; dies if it is malformed.
backup_s3_uri() {
  local uri=${BACKUP_S3_URI:-}
  if [ -z "$uri" ] && [ -r "$BACKUP_ENV" ]; then
    uri=$(sed -n 's/^BACKUP_S3_URI=//p' "$BACKUP_ENV" | tail -1)
    uri=${uri#[\"\']}; uri=${uri%[\"\']}
  fi
  [ -n "$uri" ] || return 0
  uri=${uri%/}/
  [[ "$uri" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/([A-Za-z0-9._=-]+/)+$ ]] \
    || die "BACKUP_S3_URI must look like s3://<bucket>/<prefix>/ (got '$uri')"
  printf '%s' "$uri"
}

# Uploads a file in one PutObject that carries its SHA-256 (SSE-S3). S3
# computes the checksum of the bytes it received and rejects the upload
# (BadDigest) unless it matches, stores it with the object, and reports it
# back; that report must match too. PutObject is atomic, so a failure never
# leaves a partial object. Needs only s3:PutObject; single requests are
# limited to 5 GB (dumps are a few MB).
#   s3_put_verified <file> <s3://bucket/key> <sha256 hex> [metadata k=v,...]
s3_put_verified() {
  local file=$1 uri=$2 sha=$3 bucket key b64 got
  local meta_args=()
  [ -z "${4:-}" ] || meta_args=(--metadata "$4")
  bucket=${uri#s3://}; key=${bucket#*/}; bucket=${bucket%%/*}
  [ "$(sha256sum "$file" | cut -c1-64)" = "$sha" ] || { log "$file does not match its SHA-256"; return 1; }
  b64=$(python3 -c 'import base64,sys; print(base64.b64encode(bytes.fromhex(sys.argv[1])).decode())' "$sha")
  got=$("${LIFEINUK_AWS:-aws}" s3api put-object --bucket "$bucket" --key "$key" --body "$file" \
    --server-side-encryption AES256 --checksum-algorithm SHA256 --checksum-sha256 "$b64" "${meta_args[@]}" \
    --query ChecksumSHA256 --output text) || { log "upload to $uri failed"; return 1; }
  [ "$got" = "$b64" ] || { log "S3 reports SHA-256 '$got' for $uri, expected $b64"; return 1; }
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
