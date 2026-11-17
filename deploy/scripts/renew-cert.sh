#!/usr/bin/env bash
# Renews the origin certificate (Let's Encrypt, DNS-01 through Route 53 with
# the instance role) when due, then reloads nginx. Run twice daily by
# lifeinuk-cert-renew.timer; certbot only renews within 30 days of expiry.
set -euo pipefail
source "$(dirname "$(readlink -f "$0")")/common.sh"

compose --profile tools run --rm certbot renew --non-interactive --quiet
if running proxy; then
  compose exec -T proxy nginx -s reload
  log "certificate checked; nginx reloaded"
fi
