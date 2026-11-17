#!/usr/bin/env bash
# Restores a backup (deploy/RUNBOOK.md "Restore").
#
#   restore.sh check <dump>
#       Restore drill: restores into a scratch database inside the db
#       container, prints what it contains, and drops it. Production is not
#       touched. Run it monthly and after any backup change.
#
#   restore.sh replace <dump> --confirm-replace-production
#       Replaces the production database with the dump: takes a fresh backup,
#       stops the app, keeps the current database renamed as
#       life_in_uk_before_<time>, restores into a new life_in_uk owned by the
#       application role, starts the app (Flyway validates the schema) and
#       waits until it is healthy. Static pages stay online throughout; the
#       API is unavailable for about a minute.
#
# The dump must be in the backup directory (mounted at /backups in the container).
set -euo pipefail
here=$(dirname "$(readlink -f "$0")")
source "$here/common.sh"

mode=${1:-}
dump=${2:-}
[ -n "$mode" ] && [ -n "$dump" ] || die "usage: restore.sh check|replace <dump> [--confirm-replace-production]"
inside=$(container_path "$dump")
verify_checksum "$dump" || log "no $dump.sha256; restoring without a checksum"
running db || die "the db service is not running"

case "$mode" in
  check)
    scratch=life_in_uk_restore_check
    db_sql postgres postgres "DROP DATABASE IF EXISTS $scratch"
    db_sql postgres postgres "CREATE DATABASE $scratch"
    trap 'db_sql postgres postgres "DROP DATABASE IF EXISTS $scratch" || true' EXIT
    compose exec -T db pg_restore -U postgres -d "$scratch" --no-owner --no-privileges --exit-on-error --single-transaction "$inside"
    log "restore drill OK: $(content_summary "$scratch")"
    ;;
  replace)
    [ "${3:-}" = "--confirm-replace-production" ] || die "replace needs --confirm-replace-production"
    log "production before: $(content_summary life_in_uk)"
    "$here/backup.sh" before-restore >/dev/null
    log "stopping the app"
    compose stop app
    previous="life_in_uk_before_$(date -u +%Y%m%dT%H%M%SZ)"
    db_sql postgres postgres "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'life_in_uk' AND pid <> pg_backend_pid()" >/dev/null
    db_sql postgres postgres "ALTER DATABASE life_in_uk RENAME TO $previous"
    db_sql postgres postgres "CREATE DATABASE life_in_uk OWNER life_in_uk_app"
    db_sql postgres life_in_uk "ALTER SCHEMA public OWNER TO life_in_uk_app; REVOKE ALL ON DATABASE life_in_uk FROM PUBLIC; GRANT CONNECT, TEMPORARY ON DATABASE life_in_uk TO life_in_uk_app"
    if ! compose exec -T db pg_restore -U life_in_uk_app -d life_in_uk --no-owner --no-privileges --exit-on-error --single-transaction "$inside"; then
      log "restore failed; putting the previous database back"
      db_sql postgres postgres "DROP DATABASE life_in_uk"
      db_sql postgres postgres "ALTER DATABASE $previous RENAME TO life_in_uk"
      compose up -d app
      die "restore failed; production is unchanged"
    fi
    log "production after: $(content_summary life_in_uk)"
    compose up -d app
    wait_healthy app
    log "app healthy. The previous database is kept as $previous; when satisfied: "
    log "  docker compose exec db dropdb -U postgres $previous"
    ;;
  *) die "unknown mode $mode (check or replace)" ;;
esac
