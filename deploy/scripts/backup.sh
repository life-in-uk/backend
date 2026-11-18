#!/usr/bin/env bash
# Logical backup of the production database (deploy/RUNBOOK.md "Backups").
# Run nightly by lifeinuk-backup.timer, by release.sh before every release and
# by restore.sh before any replace.
#
#   backup.sh [label]
#
# Writes <backups>/life_in_uk-<UTC time>[-label].dump (pg_dump custom format of
# the whole database, which holds only the public schema; no owners or grants) and a .sha256 next to it, checks that
# the archive is readable and holds the Guide tables, and deletes local dumps
# older than BACKUP_KEEP_DAYS (default 14).
#
# Off-instance copy: when BACKUP_S3_URI is set (environment or $BACKUP_ENV),
# the dump and its .sha256 go to <BACKUP_S3_URI><name> through s3_put_verified
# (common.sh: S3 checks the SHA-256 of what it received), with metadata
# sha256, created, label, app-image, flyway and, from release.sh,
# release-commit. A failed or unverified upload fails the backup (the local
# dump stays). BACKUP_S3_REQUIRED=1 (release.sh) refuses to run without a
# location. S3 retention is the bucket's lifecycle rule.
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"

label=${1:+-$1}
[[ "$label" =~ ^[-a-z0-9]*$ ]] || die "label may contain only a-z, 0-9 and -"
release_commit=${BACKUP_RELEASE_COMMIT:-}
[[ "$release_commit" =~ ^([0-9a-f]{40})?$ ]] || die "BACKUP_RELEASE_COMMIT must be 40 hex characters"
keep_days=${BACKUP_KEEP_DAYS:-14}
s3=$(backup_s3_uri)
[ -n "$s3" ] || [ "${BACKUP_S3_REQUIRED:-}" != 1 ] \
  || die "no off-instance backup location: set BACKUP_S3_URI in $BACKUP_ENV (RUNBOOK.md section 4)"
running db || die "the db service is not running"
umask 077

created=$(date -u +%Y%m%dT%H%M%SZ)
name="life_in_uk-$created$label.dump"
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

if [ -n "$s3" ]; then
  sha=$(cut -c1-64 "$LIFEINUK_BACKUPS/$name.sha256")
  image=$(sed -n 's/^APP_IMAGE=//p' "$LIFEINUK_DIR/.env" 2>/dev/null | tail -1)
  meta="sha256=$sha,created=$created,app-image=${image:-unknown},flyway=$(flyway_version life_in_uk)"
  meta+=${label:+,label=${label#-}}${release_commit:+,release-commit=$release_commit}
  s3_put_verified "$LIFEINUK_BACKUPS/$name" "$s3$name" "$sha" "$meta" \
    || die "the off-instance copy of $name failed or was not verified; the dump is only in $LIFEINUK_BACKUPS"
  s3_put_verified "$LIFEINUK_BACKUPS/$name.sha256" "$s3$name.sha256" "$(sha256sum "$LIFEINUK_BACKUPS/$name.sha256" | cut -c1-64)" \
    || die "the off-instance copy of $name.sha256 failed or was not verified"
  log "copied to $s3$name (SHA-256 $sha verified by S3)"
fi

find "$LIFEINUK_BACKUPS" -maxdepth 1 -name 'life_in_uk-*.dump*' -mtime "+$keep_days" -print -delete | sed 's/^/deleted old backup: /' >&2
printf '%s\n' "$LIFEINUK_BACKUPS/$name"
