#!/usr/bin/env bash
set -euo pipefail
umask 077

backup_dir="/home/yejun_guan/projects/life-in-uk-frontend/.local/n8n-backups"
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
output="$backup_dir/life-uk-n8n-${stamp}.dump.gpg"

if [[ ! -r "$HOME/.pgpass" ]]; then
  echo "Create a mode-0600 ~/.pgpass entry for life_uk_n8n before backup." >&2
  exit 2
fi

PGPASSFILE="$HOME/.pgpass" pg_dump \
  --host=127.0.0.1 --port=5432 --username=life_uk_n8n \
  --dbname=life_in_uk --schema=life_uk_n8n \
  --format=custom --no-owner --no-privileges \
  | gpg --symmetric --cipher-algo AES256 --output "$output"

chmod 600 "$output"
echo "Encrypted n8n-schema backup written: $output"
