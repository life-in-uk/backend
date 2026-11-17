#!/bin/bash
# Runs once, when the pgdata volume is first initialised (docker-entrypoint-initdb.d).
# The application connects as the non-superuser life_in_uk_app, which owns the
# database and its public schema so that Flyway can migrate it. The postgres
# superuser is only used by the operator scripts inside the container.
set -euo pipefail
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set in db.env}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE life_in_uk_app LOGIN PASSWORD :'app_password';
ALTER DATABASE life_in_uk OWNER TO life_in_uk_app;
ALTER SCHEMA public OWNER TO life_in_uk_app;
REVOKE ALL ON DATABASE life_in_uk FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE life_in_uk TO life_in_uk_app;
SQL
