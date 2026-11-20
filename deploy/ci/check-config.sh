#!/usr/bin/env bash
# Static checks of the production deployment configuration (docs/ci-cd.md):
# assumptions the runbook relies on that a careless edit could break. Needs
# Docker Compose (to resolve compose.yml exactly as the server does) and python3.
#
#   deploy/ci/check-config.sh
set -euo pipefail
repo=$(git -C "$(dirname "$(readlink -f "$0")")" rev-parse --show-toplevel)
cd "$repo"
errors=0
fail() { echo "::error title=Unsafe deployment configuration::$*"; errors=$((errors + 1)); }
ok() { echo "  ✓ $*"; }

# 1. No secrets or data files in the repository.
leaked=$(git ls-files | grep -E '(^|/)\.env$|\.env\.(local|prod)|(^|/)deploy/env/[^/]+\.env$|\.(pem|key|p12|dump)$|id_(rsa|ed25519)' || true)
[ -z "$leaked" ] && ok "no env, key or dump files tracked" || fail "tracked secret or data files: $leaked"
filled=$(grep -HnE '^(DB_PASSWORD|POSTGRES_PASSWORD|APP_DB_PASSWORD|ORIGIN_VERIFY_SECRET|[A-Z_]*API_KEY|EDITORIAL_[A-Z_]*PASSWORD|LIFE_IN_UK_EDITORIAL_[A-Z_]*TOKEN)=.+' deploy/env/*.example || true)
[ -z "$filled" ] && ok "env templates carry no secret values" || fail "env template with a value: $filled"

# 2. Production env template: owner decisions and fixed rules (RUNBOOK.md).
want() { grep -qx "$1" deploy/env/backend.env.example && ok "backend.env: $1" || fail "deploy/env/backend.env.example must contain '$1'"; }
want "LIFE_IN_UK_PUBLIC_BASE_URL=https://lifeinuk.info"
want "RAIL_ACCESS_ENABLED=false"
want "TRAVEL_REFRESH_UNDERGROUND_ENABLED=false"
want "TRAVEL_REFRESH_ROADS_ENABLED=false"
want "MARKET_INDICES_PUBLIC_ENABLED=false"
if grep -qE '^MARKET_INDICES_(COLLECTOR_KEYS|PUBLIC_SOURCES)=' deploy/env/backend.env.example; then
  fail "backend.env.example configures a market index collector key or public source"
else ok "no market index collector key or public source configured"; fi
if grep -qE '^(SPRING_PROFILES_ACTIVE|LIFE_IN_UK_EDITORIAL_LOCAL_REVIEW_TOKEN|EDITORIAL_[A-Z_]+|LIFE_IN_UK_EDITORIAL_REVIEW_TOKEN)=' deploy/env/backend.env.example; then
  fail "backend.env.example enables a profile or editorial credentials"
else ok "no profile and no editorial credentials enabled"; fi

# 3. Application defaults stay off when the environment does not set them.
for default in 'RAIL_ACCESS_ENABLED:false' 'TRAVEL_REFRESH_UNDERGROUND_ENABLED:false' 'TRAVEL_REFRESH_ROADS_ENABLED:false' 'MARKET_INDICES_PUBLIC_ENABLED:false'; do
  grep -qF "\${$default}" src/main/resources/application.yml && ok "application.yml default \${$default}" || fail "application.yml must default \${$default}"
done
grep -q 'ddl-auto: validate' src/main/resources/application.yml && ok "Hibernate validates, never changes, the schema" || fail "spring.jpa.hibernate.ddl-auto must be validate"
if grep -rqiE 'flyway\.(clean-disabled|cleanDisabled)\s*[:=]\s*false|out-of-order\s*:\s*true|baseline-on-migrate\s*:\s*true' src/main/resources; then
  fail "Flyway clean, out-of-order or baseline-on-migrate is enabled"
else ok "Flyway clean, out-of-order and baseline-on-migrate not enabled"; fi

# 4. Container hardening and pinned images.
grep -q '^USER 10001:10001' Dockerfile && ok "image runs as uid 10001" || fail "Dockerfile must run as USER 10001:10001"
grep -qE '^FROM [^ ]+@sha256:[0-9a-f]{64}' Dockerfile && ok "base image pinned by digest" || fail "Dockerfile base image must be pinned by digest"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
touch "$tmp/db.env" "$tmp/backend.env" "$tmp/proxy.env"
LIFEINUK_ETC=$tmp APP_IMAGE=lifeinuk-backend:check docker compose -f deploy/compose.yml config --format json > "$tmp/compose.json"
python3 - "$tmp/compose.json" <<'PY' || errors=$((errors + 1))
import json, re, sys
services = json.load(open(sys.argv[1]))["services"]
problems = []
def check(ok, message):
    print(f"  ✓ {message}" if ok else f"::error title=Unsafe deployment configuration::{message}")
    if not ok: problems.append(message)
for name, service in services.items():
    if name == "app": continue
    check(bool(re.search(r"@sha256:[0-9a-f]{64}$", service["image"])), f"{name} image pinned by digest")
check(not services["db"].get("ports"), "db publishes no port")
check(all(n.get("internal") for k, n in json.load(open(sys.argv[1]))["networks"].items() if k == "data"), "data network is internal")
app_ports = services["app"].get("ports", [])
check(len(app_ports) == 1 and app_ports[0].get("host_ip") == "127.0.0.1", "app port bound to loopback only")
proxy_ports = services["proxy"].get("ports", [])
check([p.get("target") for p in proxy_ports] == [8443] and [p.get("published") for p in proxy_ports] == ["443"], "proxy publishes only 443")
env = services["app"].get("environment", {})
check(env.get("SPRING_FLYWAY_ENABLED") == "true" and "SPRING_PROFILES_ACTIVE" not in env, "app: Flyway on, no Spring profile")
for name in ("app", "proxy"):
    check(services[name].get("read_only") is True, f"{name} root filesystem read-only")
    check("no-new-privileges:true" in services[name].get("security_opt", []), f"{name} no-new-privileges")
check(any(v.get("source") == "pgdata" and v.get("target") == "/var/lib/postgresql/data" for v in services["db"]["volumes"]), "database data in the named volume pgdata")
sys.exit(1 if problems else 0)
PY

# 5. nginx keeps the origin protections.
conf=deploy/nginx/templates/lifeinuk.conf.template
for rule in 'map $http_x_origin_verify $origin_verified' 'limit_req_zone $viewer_ip zone=api_viewer' 'ssl_protocols TLSv1.2 TLSv1.3'; do
  grep -qF "$rule" "$conf" && ok "nginx: $rule" || fail "nginx template lost: $rule"
done

echo
[ "$errors" = 0 ] && echo "deployment configuration: OK" || { echo "deployment configuration: $errors problem(s)"; exit 1; }
