#!/usr/bin/env bash
# Workstation: fingerprints of env values, to compare with the server's
# `set-env.sh --fingerprint` without showing either value
# (docs/travel-release.md).
#
#   deploy/ops/env-fingerprint.sh <env file> NAME ...
#
# Prints "NAME sha256:<first 8 hex>" or "NAME empty". Reads the file; changes nothing.
set -euo pipefail
exec "$(dirname "$(readlink -f "$0")")/../scripts/set-env.sh" --fingerprint "$@"
