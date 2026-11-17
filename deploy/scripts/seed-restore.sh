#!/usr/bin/env bash
# First deployment only: loads the approved seed made by
# deploy/seed/export-approved-seed.sh into the new, empty production database.
#
#   seed-restore.sh <backups>/lifeinuk-seed-<time>.dump
#
# Refuses unless the checksum matches, the app is stopped (so Flyway has not
# created a schema yet) and the database has no tables. Restores as the
# application role in one transaction; the seed's Flyway history (V1..V15,
# the migrations of this backend) is restored with it, so the app's Flyway
# validates instead of migrating. Start the app afterwards (RUNBOOK.md).
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"

seed=${1:-}
[ -n "$seed" ] || die "usage: seed-restore.sh <seed dump>"
inside=$(container_path "$seed")
verify_checksum "$seed" || die "$seed.sha256 is required for a seed"
running db || die "the db service is not running"
! running app || die "stop the app first: the seed must go into an empty database"
tables=$(db_sql postgres life_in_uk "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
[ "$tables" = 0 ] || die "life_in_uk already has $tables tables; a seed only goes into a new database (use restore.sh for backups)"

compose exec -T db pg_restore -U life_in_uk_app -d life_in_uk --no-owner --no-privileges --exit-on-error --single-transaction "$inside"
log "seed restored: $(content_summary life_in_uk)"
log "next: docker compose up -d app   (Flyway validates; then RUNBOOK.md 'Verify the first deployment')"
