# Native n8n local setup and owner action

## Verified local state

- PostgreSQL is the WSL PostgreSQL 16 cluster on `127.0.0.1:5432`; the database is `life_in_uk`.
- A mode-0600 custom-format backup is at `/home/yejun_guan/.local/state/life-in-uk/backups/life_in_uk_before_v12_pg16_20261111.dump`. It restored successfully into a separate temporary PostgreSQL 16 cluster. The restored database had Flyway version 11 and the same 10 legacy Guides.
- V12 was applied locally after the restore check; Flyway applied exactly one migration, 11 to 12. The 10 legacy Guides were unchanged, the multilingual batch table is empty, and workflow activation remains empty.
- n8n 2.47.4 is installed under `/home/yejun_guan/.local/opt/n8n-2.47.4`; it reports version 2.47.4 under Node 24.22.3. The Docker POC container, named volume, network and encryption key were left intact.
- The protected local n8n environment file already identifies database/schema/login `life_in_uk` / `life_uk_n8n`. PostgreSQL confirms the `life_uk_n8n` role and schema do not yet exist. The app role cannot create roles.

## Required owner-only PostgreSQL action

Run this in an owner/admin terminal. It creates no database and grants no business-table access. Do not run it as `life_in_uk_app`.

```sh
sudo -u postgres psql -X --set=ON_ERROR_STOP=1 --dbname=life_in_uk
```

At the `psql` prompt, run:

```sql
BEGIN;
CREATE ROLE life_uk_n8n LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT CONNECT ON DATABASE life_in_uk TO life_uk_n8n;
REVOKE CREATE ON DATABASE life_in_uk FROM life_uk_n8n;
REVOKE ALL ON SCHEMA public FROM life_uk_n8n;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM life_uk_n8n;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM life_uk_n8n;
CREATE SCHEMA life_uk_n8n AUTHORIZATION life_uk_n8n;
ALTER ROLE life_uk_n8n IN DATABASE life_in_uk SET search_path = life_uk_n8n;
COMMIT;
\password life_uk_n8n
```

At the password prompts, set the password to the value stored in the existing mode-0600 `/home/yejun_guan/projects/life-in-uk-frontend/.local/n8n.env` file, or change both through the owner’s local secret-management process. Never put it in SQL, command arguments, shell history, workflow exports, model prompts or logs. Then verify before starting n8n:

```sql
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolbypassrls
FROM pg_roles WHERE rolname = 'life_uk_n8n';
SELECT nspname, pg_get_userbyid(nspowner) AS owner FROM pg_namespace WHERE nspname = 'life_uk_n8n';
SELECT count(*) AS business_relations_with_any_table_privilege
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','f')
  AND (has_table_privilege('life_uk_n8n', c.oid, 'SELECT')
    OR has_table_privilege('life_uk_n8n', c.oid, 'INSERT')
    OR has_table_privilege('life_uk_n8n', c.oid, 'UPDATE')
    OR has_table_privilege('life_uk_n8n', c.oid, 'DELETE')
    OR has_table_privilege('life_uk_n8n', c.oid, 'TRUNCATE'));
SELECT count(*) AS role_memberships FROM pg_auth_members
WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'life_uk_n8n');
```

Expected: `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOINHERIT`, `NOBYPASSRLS`; schema owner `life_uk_n8n`; zero table privileges and zero role memberships. If the table-privilege query is nonzero, stop and review the grants. Do not revoke shared `PUBLIC` privileges globally.

## Native service

The systemd user unit is maintained at `/home/yejun_guan/projects/life-in-uk-backend/ops/n8n/life-uk-n8n.service`, using Node 24.22.3, n8n 2.47.4 and PostgreSQL 16’s readiness tool. It reads `/home/yejun_guan/projects/life-in-uk-frontend/.local/n8n.env` (mode 0600, ignored by Git). The unit is installed for the user but remains disabled and inactive. The owner-provisioned role/schema are present with no business-table privileges; password authentication with the configured value failed on 2026-11-12, so do not start until synchronized and reverified.

After the owner action and privilege verification:

```sh
mkdir -p /home/yejun_guan/.config/systemd/user
cp /home/yejun_guan/projects/life-in-uk-backend/ops/n8n/life-uk-n8n.service /home/yejun_guan/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now life-uk-n8n.service
systemctl --user status life-uk-n8n.service --no-pager
curl --fail http://127.0.0.1:5678/healthz
```

Stop/restart with `systemctl --user stop life-uk-n8n.service` / `systemctl --user restart life-uk-n8n.service`. The service binds to 127.0.0.1. It does not mount the backend, Docker socket, or host directories; the n8n login has access only to its own schema. Execution data pruning is enabled at seven days, and execution payload saving is disabled to avoid retaining article bodies in n8n history.

## Workflow credentials and mock pipeline

Import the workflows in `ops/n8n/workflows/` only after the service is available. Create three editorial bearer credentials locally: `Life UK Editorial Draft API`, `Life UK Editorial Review API`, and `Life UK Editorial Approval API`. Use separate random tokens for the Spring Boot settings `life-in-uk.editorial.draft-token`, `life-in-uk.editorial.review-token`, and `life-in-uk.editorial.approval-token`. Configure `life-in-uk.editorial.approval-actor` to the human actor identifier used in the approval audit. The approval credential must remain outside n8n; it is documented for the owner-operated Spring Boot approval interface only. Bind the draft and review credentials to the local workflow nodes. The workflow has no approval or publication node.

The local mock workflow uses a separate fourth, local-only `Life UK Editorial Mock Review API` credential, backed by `life-in-uk.editorial.mock-review-token`. This credential is accepted only on review routes and the backend derives `reviewer=mock-adapter` from that credential; review payloads cannot choose their reviewer identity. The mock identity is rejected by the approval service. Do not configure this mock token in production. The normal review token is independently recorded as `claude-independent`.

The first workflow calls only the fixed loopback mock adapter at `127.0.0.1:8091`. It is labeled mock and returns synthetic content backed by `.invalid` URLs; it does not call Sol or Claude and must never be published. Start it manually for the local pipeline run:

```sh
PATH=/home/yejun_guan/.local/opt/node-v24.22.3/bin:$PATH node ops/n8n/local-mock-adapter.mjs
```

The expiry workflow is scheduled every six hours after activation but emits a dry-run report only. There is no cleanup endpoint or delete node.
