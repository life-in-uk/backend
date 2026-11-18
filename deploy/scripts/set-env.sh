#!/usr/bin/env bash
# Sets values in an env file under /etc/lifeinuk without putting secrets on a
# command line, in logs or in SSM command history (deploy/RUNBOOK.md section 5,
# docs/travel-release.md). Run as root in an interactive session.
#
#   set-env.sh <env file> NAME ... NAME=VALUE ...
#       NAME          a secret: prompted for with hidden input (an empty answer
#                     keeps the current value)
#       NAME=VALUE    a non-secret setting; refused for names that look secret
#                     (*KEY*, *PASSWORD*, *SECRET*, *TOKEN*)
#   set-env.sh --fingerprint <env file> NAME ...
#       prints "NAME sha256:<first 8 hex>" (or "NAME empty"); the same output
#       as deploy/ops/env-fingerprint.sh on a local copy, to compare without
#       showing values.
#
# Writes atomically (same directory, mode 0600, owner kept), after saving the
# old file to $SET_ENV_BACKUPS (default /root/lifeinuk-env-backups, 0700).
# Existing lines are replaced in place, new ones appended; comments and other
# lines are kept. Prints only names, non-secret values and fingerprints. The
# running containers are not touched: `docker compose up -d app` (or a
# release) applies the change.
set -euo pipefail
umask 077

die() { printf 'set-env: %s\n' "$*" >&2; exit 1; }
secret_name() { [[ "$1" =~ (KEY|PASSWORD|SECRET|TOKEN) ]]; }
fingerprint() { if [ -n "$1" ]; then printf 'sha256:%s' "$(printf '%s' "$1" | sha256sum | cut -c1-8)"; else printf 'empty'; fi; }
current() { sed -n "s/^\(export \)\{0,1\}$1=//p" "$2" | tail -1 | sed "s/^[\"']//; s/[\"']\$//"; }

if [ "${1:-}" = --fingerprint ]; then
  file=${2:?usage: set-env.sh --fingerprint <env file> NAME ...}; shift 2
  [ -r "$file" ] || die "$file not readable"
  for name in "$@"; do
    [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "bad name $name"
    value=$(current "$name" "$file")
    if [ -n "$value" ]; then echo "$name $(fingerprint "$value")"; else echo "$name empty"; fi
  done
  exit 0
fi

file=${1:?usage: set-env.sh <env file> NAME ... NAME=VALUE ...}; shift
[ -f "$file" ] || die "$file not found"
[ $# -gt 0 ] || die "nothing to set"
declare -A new=()
order=()
for arg in "$@"; do
  name=${arg%%=*}
  [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "bad name $name"
  [ -z "${new[$name]+x}" ] || die "$name given twice"
  if [[ "$arg" == *=* ]]; then
    secret_name "$name" && die "$name looks secret: give the name only and enter the value at the prompt"
    value=${arg#*=}
  else
    secret_name "$name" || die "$name does not look secret: give it as $name=VALUE"
    [ -t 0 ] || [ -n "${SET_ENV_STDIN:-}" ] || die "secrets are read from a terminal (interactive session)"
    printf '%s (input hidden, empty keeps the current value): ' "$name" >&2
    IFS= read -rs value || true
    printf '\n' >&2
    [ -n "$value" ] || value=$(current "$name" "$file")
  fi
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "$name: value has a line break"
  if secret_name "$name"; then [[ -z "$value" || "$value" =~ ^[[:graph:]]+$ ]] || die "$name: value has spaces or control characters"; fi
  new[$name]=$value
  order+=("$name")
done

backups=${SET_ENV_BACKUPS:-/root/lifeinuk-env-backups}
install -d -m 0700 "$backups"
backup="$backups/$(basename "$file").$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$file" "$backup"

tmp=$(mktemp "$(dirname "$file")/.$(basename "$file").XXXXXX")
trap 'rm -f "$tmp"' EXIT
declare -A done_=()
while IFS= read -r line || [ -n "$line" ]; do
  name=${line%%=*}
  if [[ "$line" == *=* && -n "${new[$name]+x}" ]]; then
    [ -z "${done_[$name]+x}" ] && printf '%s=%s\n' "$name" "${new[$name]}"
    done_[$name]=1
  else
    printf '%s\n' "$line"
  fi
done < "$file" > "$tmp"
for name in "${order[@]}"; do
  [ -n "${done_[$name]+x}" ] || printf '%s=%s\n' "$name" "${new[$name]}" >> "$tmp"
done
chown --reference="$file" "$tmp" 2>/dev/null || true
chmod 0600 "$tmp"
mv "$tmp" "$file"
trap - EXIT

for name in "${order[@]}"; do
  if secret_name "$name"; then echo "$name $(fingerprint "${new[$name]}")"; else echo "$name=${new[$name]}"; fi
done
echo "saved the previous file as $backup (restore: cp -p $backup $file)"
