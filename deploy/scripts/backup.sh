#!/usr/bin/env bash
# Logical backup of the production database (deploy/RUNBOOK.md "Backups").
# Run nightly by lifeinuk-backup.timer, and by restore.sh before any replace.
#
#   backup.sh [label]
#
# Writes <backups>/life_in_uk-<UTC time>[-label].dump (pg_dump custom format of
# the whole database, which holds only the public schema; no owners or grants) and a .sha256 next to it, checks that
# the archive is readable and holds the Guide tables, copies both to
# BACKUP_S3_URI if set (SSE-S3), and deletes local dumps older than
# BACKUP_KEEP_DAYS (default 14). S3 retention is the bucket's lifecycle rule.
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"

label=${1:+-$1}
[[ "$label" =~ ^[-a-z0-9]*$ ]] || die "label may contain only a-z, 0-9 and -"
keep_days=${BACKUP_KEEP_DAYS:-14}
running db || die "the db service is not running"
umask 077

name="life_in_uk-$(date -u +%Y%m%dT%H%M%SZ)$label.dump"
partial=".$name.partial"
trap 'compose exec -T db rm -f "/backups/$partial" >/dev/null 2>&1 || true' EXIT

log "dumping to $LIFEINUK_BACKUPS/$name"
compose exec -T db pg_dump -U postgres -d life_in_uk -Fc --no-owner --no-privileges -f "/backups/$partial"
listing=$(compose exec -T db pg_restore --list "/backups/$partial")
for table in guide guide_publication guide_locale_current flyway_schema_history; do
  grep -q "TABLE DATA public $table " <<<"$listing" || die "the dump has no data for $table; not keeping it"
done
compose exec -T db mv "/backups/$partial" "/backups/$name"
(cd "$LIFEINUK_BACKUPS" && sha256sum "$name" > "$name.sha256")
log "backup OK: $name ($(du -h "$LIFEINUK_BACKUPS/$name" | cut -f1))"

if [ -n "${BACKUP_S3_URI:-}" ]; then
  aws s3 cp --only-show-errors --sse AES256 "$LIFEINUK_BACKUPS/$name" "${BACKUP_S3_URI%/}/$name"
  aws s3 cp --only-show-errors --sse AES256 "$LIFEINUK_BACKUPS/$name.sha256" "${BACKUP_S3_URI%/}/$name.sha256"
  log "copied to ${BACKUP_S3_URI%/}/$name"
fi

find "$LIFEINUK_BACKUPS" -maxdepth 1 -name 'life_in_uk-*.dump*' -mtime "+$keep_days" -print -delete | sed 's/^/deleted old backup: /' >&2
printf '%s\n' "$LIFEINUK_BACKUPS/$name"
