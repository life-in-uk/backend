#!/usr/bin/env bash
# Produces the seed for the FIRST production deployment from the local
# database, without changing it (deploy/RUNBOOK.md "First deployment").
#
#   deploy/seed/export-approved-seed.sh [--env-file FILE] [--api URL] [--out DIR]
#
#   --env-file  local backend env file with DB_URL/DB_USERNAME/DB_PASSWORD and
#               LIFE_IN_UK_PUBLIC_BASE_URL (default ~/.config/life-in-uk/backend-8188.env)
#   --api       the running local backend to compare with (default http://127.0.0.1:8188)
#   --out       output directory (default ~/.local/state/life-in-uk/production-seed/<UTC time>)
#
# Steps:
#   1. pg_dump (plain SQL) of the local public schema only (the n8n schema and
#      anything else in the database are never read). Read-only for the local database.
#   2. Restore into a disposable PostgreSQL 16 container (the production image)
#      and apply prune-unapproved.sql: approved, public application data only.
#   3. Dump the result with the container's pg_dump 16 → the seed.
#   4. Prove the seed: restore it into a fresh database exactly as production
#      will (as the non-superuser application role, one transaction), start the
#      built backend jar on it with Flyway enabled, and require every public
#      endpoint to answer byte-for-byte like the local backend (compare-api.py).
#   5. Write seed.dump, seed.dump.sha256, manifest.json, compare.txt; remove the container.
set -euo pipefail
repo=$(git -C "$(dirname "$(readlink -f "$0")")" rev-parse --show-toplevel)
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

env_file="$HOME/.config/life-in-uk/backend-8188.env"
api=http://127.0.0.1:8188
out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) env_file=$2; shift 2 ;;
    --api) api=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done
stamp=$(date -u +%Y%m%dT%H%M%SZ)
out=${out:-$HOME/.local/state/life-in-uk/production-seed/$stamp}
image=$(sed -n 's/^ *image: \(postgres:16[^ ]*\)$/\1/p' "$repo/deploy/compose.yml")
jar="$repo/target/backend-0.0.1-SNAPSHOT.jar"
for tool in pg_dump pg_restore psql pg_isready docker java python3 curl; do command -v "$tool" >/dev/null || die "$tool is required"; done
[ -n "$image" ] || die "could not read the postgres image from deploy/compose.yml"
[ -f "$jar" ] || die "$jar not found: run mvn verify first"
[ -f "$env_file" ] || die "$env_file not found"
curl -fsS -o /dev/null "$api/api/guides" || die "the local backend at $api is not answering"

# Read only the four values needed; other secrets in the env file are not exported.
read_env() { (set -a; . "$env_file"; set +a; printf '%s' "${!1:-}"); }
db_url=$(read_env DB_URL); db_user=$(read_env DB_USERNAME); public_base=$(read_env LIFE_IN_UK_PUBLIC_BASE_URL)
[[ "$db_url" =~ ^jdbc:postgresql://([^:/]+)(:([0-9]+))?/([^?]+) ]] || die "unexpected DB_URL format in $env_file"
db_host=${BASH_REMATCH[1]}; db_port=${BASH_REMATCH[3]:-5432}; db_name=${BASH_REMATCH[4]}

mkdir -p "$out"; chmod 700 "$out"
work=$(mktemp -d); container="lifeinuk-seed-$stamp"; app_pid=""
cleanup() {
  [ -n "$app_pid" ] && kill "$app_pid" 2>/dev/null && wait "$app_pid" 2>/dev/null || true
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
umask 077

log "1/5 dumping the local public schema ($db_name on $db_host:$db_port, read-only)"
# Plain SQL, so the production psql 16 can load it whatever the local client
# version is. A newer pg_dump adds "SET transaction_timeout = 0;" to the
# session header (a PostgreSQL 17+ setting); only that header line is dropped.
# Every schema except public is excluded by name (life_uk_n8n among them), rather
# than selecting public with -n, which would also emit CREATE SCHEMA public.
export PGPASSWORD=$(read_env DB_PASSWORD)
exclude=()
while read -r schema; do [ -n "$schema" ] && exclude+=(-N "$schema"); done < <(psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -XAtq \
  -c "SELECT quote_ident(nspname) FROM pg_namespace WHERE nspname <> 'public' AND nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'")
# Extensions installed in other schemas (n8n's uuid-ossp) are excluded too.
while read -r extension; do [ -n "$extension" ] && exclude+=(--exclude-extension "$extension"); done < <(psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -XAtq \
  -c "SELECT e.extname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE n.nspname NOT IN ('public', 'pg_catalog')")
pg_dump -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" "${exclude[@]}" -Fp --no-owner --no-privileges -f "$work/local-public.sql"
unset PGPASSWORD
log "  excluded: ${exclude[*]}"
grep -q -E '^CREATE (SCHEMA|EXTENSION)' "$work/local-public.sql" && die "the dump still creates a schema or extension"
sed -i '1,40{/^SET transaction_timeout = 0;$/d}' "$work/local-public.sql"

log "2/5 restoring into a disposable $image container and pruning"
pw=$(openssl rand -hex 24)
docker run -d --name "$container" -e POSTGRES_PASSWORD="$pw" -e POSTGRES_DB=life_in_uk -p 127.0.0.1::5432 "$image" >/dev/null
port=$(docker port "$container" 5432/tcp | head -1 | sed 's/.*://')
for _ in $(seq 1 60); do pg_isready -q -h 127.0.0.1 -p "$port" -d life_in_uk && break; sleep 1; done
pg_isready -q -h 127.0.0.1 -p "$port" -d life_in_uk || die "scratch database did not start"
sleep 2  # the image restarts the server once after initialisation
for _ in $(seq 1 30); do PGPASSWORD=$pw psql -h 127.0.0.1 -p "$port" -U postgres -d life_in_uk -Atqc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
export PGPASSWORD=$pw
docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 --single-transaction -U postgres -d life_in_uk < "$work/local-public.sql" >/dev/null
counts_sql="SELECT string_agg(format('\"%s\":%s', relname, n), ',' ORDER BY relname) FROM (
  SELECT c.relname, (xpath('/row/n/text()', query_to_xml(format('select count(*) as n from public.%I', c.relname), false, true, '')))[1]::text::bigint AS n
  FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace WHERE s.nspname = 'public' AND c.relkind = 'r') t"
before=$(psql -h 127.0.0.1 -p "$port" -U postgres -d life_in_uk -Atqc "$counts_sql")
psql -h 127.0.0.1 -p "$port" -U postgres -d life_in_uk -X -q -f "$repo/deploy/seed/prune-unapproved.sql"
after=$(psql -h 127.0.0.1 -p "$port" -U postgres -d life_in_uk -Atqc "$counts_sql")

log "3/5 writing the seed with the production pg_dump"
docker exec "$container" pg_dump -U postgres -d life_in_uk -Fc --no-owner --no-privileges -f /tmp/seed.dump
docker cp -q "$container:/tmp/seed.dump" "$out/seed.dump"
chmod 600 "$out/seed.dump"
(cd "$out" && sha256sum seed.dump > seed.dump.sha256)

log "4/5 proving the seed: restore as the application role, start the backend with Flyway, compare every endpoint"
app_pw=$(openssl rand -hex 24)
docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres --set pw="$app_pw" <<'SQL'
CREATE ROLE life_in_uk_app LOGIN PASSWORD :'pw';
CREATE DATABASE seed_verify OWNER life_in_uk_app;
\c seed_verify
ALTER SCHEMA public OWNER TO life_in_uk_app;
SQL
docker exec "$container" pg_restore -U life_in_uk_app -d seed_verify --no-owner --no-privileges --exit-on-error --single-transaction /tmp/seed.dump
app_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
env -i PATH="$PATH" HOME="$HOME" \
  DB_URL="jdbc:postgresql://127.0.0.1:$port/seed_verify" DB_USERNAME=life_in_uk_app DB_PASSWORD="$app_pw" \
  LIFE_IN_UK_PUBLIC_BASE_URL="$public_base" \
  java -jar "$jar" --server.address=127.0.0.1 --server.port="$app_port" --spring.flyway.enabled=true > "$out/verify-app.log" 2>&1 &
app_pid=$!
for _ in $(seq 1 120); do curl -fsS -o /dev/null "http://127.0.0.1:$app_port/api/guides" 2>/dev/null && break; kill -0 "$app_pid" 2>/dev/null || break; sleep 1; done
curl -fsS -o /dev/null "http://127.0.0.1:$app_port/api/guides" || die "the backend did not start on the seed (see $out/verify-app.log)"
grep -q 'Successfully validated 15 migrations' "$out/verify-app.log" || die "Flyway did not validate the seed's 15 migrations (see $out/verify-app.log)"
grep -q -E 'Migrating schema|Successfully applied' "$out/verify-app.log" && die "Flyway migrated the seed; its history does not match this backend"
python3 "$repo/deploy/seed/compare-api.py" "$api" "http://127.0.0.1:$app_port" --report "$out/compare.txt" >&2 \
  || die "the seed does not serve the same public content as the local backend (see $out/compare.txt)"
travel=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$app_port/api/travel/underground")

log "5/5 manifest"
python3 - "$out/manifest.json" <<PY
import json, sys
manifest = {
    "createdAt": "$stamp",
    "backendCommit": "$(git -C "$repo" rev-parse HEAD)",
    "postgresImage": "$image",
    "sha256": open("$out/seed.dump.sha256").read().split()[0],
    "flyway": "V1..V15 validated by the backend on the restored seed",
    "rowsBefore": json.loads("{" + '''$before''' + "}"),
    "rowsAfter": json.loads("{" + '''$after''' + "}"),
    "compare": open("$out/compare.txt").read().splitlines()[:4],
    "travelUndergroundStatusOnSeed": $travel,
}
json.dump(manifest, open(sys.argv[1], "w"), indent=1)
PY
log "seed ready: $out/seed.dump ($(du -h "$out/seed.dump" | cut -f1)); manifest $out/manifest.json"
printf '%s\n' "$out/seed.dump"
