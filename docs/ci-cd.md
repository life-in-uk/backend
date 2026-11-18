# CI/CD (backend)

Built on the existing deployment (`deploy/RUNBOOK.md`): the same image
(`build-image.sh`), the same Compose project on the instance, the same
backups. GitHub only adds testing, delivery and an approval step. The
frontend has its own pipeline (frontend repository, `docs/ci-cd.md`).

| Workflow | When | AWS access |
|---|---|---|
| `.github/workflows/ci.yml` | every pull request and push to `main` | none |
| `.github/workflows/deploy-production.yml` | manual, then owner approval | OIDC role, release job only |

```
CI (push to main) ── mvn verify ─► image from that jar ─► stack test ─► artifact backend-image (30 days)
Deploy production ── same artifact ─► S3 releases/backend/<commit>/ ─► SSM LifeInUK-BackendRelease
                                                                       └► /opt/lifeinuk/scripts/release.sh on the instance
```

No ECR: one instance, one image per release, ~150 MB through S3 (lifecycle
60 days) costs cents. No SSH key in GitHub: the instance's SSM agent runs a
single, fixed command document.

## CI

One job (the jar is built and tested once): actionlint and shellcheck;
`deploy/ci/check-config.sh` (no tracked secrets, Rail and travel refresh off,
no profile or editorial credentials, Hibernate `validate`, pinned images, no
database port, loopback-only app, read-only containers, nginx origin
protections); `deploy/ci/check-migrations.py --base` (existing migrations
unchanged, new ones `V<n>__*.sql` above the latest, statements that can lose
data or break the previous image flagged); `mvn verify` (Flyway applies every
migration to a temporary PostgreSQL 16) with a test report in the job
summary; the production image; and `deploy/test/ci-stack-test.sh`:

- the image on an empty database through the real `compose.yml`, nginx
  origin protections, container hardening;
- `release.sh` and the SSM document (through a fake AWS CLI that applies the
  document's parameter rules): deploy, idempotent redeploy, refusals (deploy
  files out of sync, wrong archive checksum, unapproved migration, injection
  in a parameter), approved migration, rollback across a newer schema,
  automatic return to the previous image when a release never becomes
  healthy, roll-forward, lock, pruning, audit log, and that rows survive.

`deploy/test/stack-test.sh` (seed, content comparison with the local backend)
stays the workstation test for the first deployment and backups.

## Release

1. Merge to `main`; wait for CI on `main` (it produces `backend-image`).
2. If `deploy/compose.yml`, `nginx/`, `db/`, `scripts/` or `systemd/` changed:
   sync them to the server first (RUNBOOK.md section 3). The deploy refuses
   while the server's copy differs (digest check).
3. Actions → **Deploy production** → `action` = deploy, `ref` blank (current
   `main`) or a commit on `main`. Approve the **production** environment.
4. The job reads production's status and gates the release:
   - **Pending Flyway migrations** (newer than production's history) are shown
     in the run summary with their SQL. The job stops until you rerun with
     `approve_migrations` = exactly those versions (e.g. `16`), and, if any
     statement is flagged (drop, truncate, delete, update, rename, type
     change, NOT NULL), also `destructive_migrations_reviewed`. Before
     approving, decide whether the *previous* image still works on the new
     schema: if not, a rollback will need the pre-release backup.
   - The server repeats the migration check against the image's own jar.
5. On the server `release.sh deploy`: checks the archive SHA-256 from CI and
   the image's revision label, takes `backup.sh pre-release-<commit>`, switches
   `APP_IMAGE`, starts the app (Flyway migrates if approved), waits for
   health and checks the API. If the app is not healthy it puts the previous
   image back and the job fails. Then the job runs `deploy/ci/public-smoke.sh`
   through CloudFront.
6. The summary shows the commit, archive SHA-256, image ID, Flyway before and
   after, the backup name and the smoke results. `/opt/lifeinuk/releases.log`
   on the server keeps every step.

Database rule: no workflow step resets, reseeds, truncates or restores the
database. `seed-restore.sh` is first-deployment only and refuses a database
with tables; `restore.sh replace` is a manual operator action.

## Rollback

Actions → **Deploy production** → `action` = rollback. It switches back to the
image recorded as `previous` (the server keeps the last three). A second
rollback rolls forward again.

- No migration since the previous image: safe, about a minute.
- The release applied migrations: the rollback stops unless
  `allow_newer_schema` is ticked. Flyway in the old image ignores the newer
  migrations, so it starts if the schema change was additive. If the old code
  cannot work with the new schema, do not roll the image back: fix forward,
  or restore the `pre-release-<commit>` backup by hand
  (RUNBOOK.md section 4, `restore.sh replace … --confirm-replace-production`,
  then `release.sh rollback true`). A restore loses everything written since
  the backup.

`action` = status shows the running image, Flyway version, deploy-files
digest and rollback target without changing anything.

## One-time setup (owner approval required; nothing here has been applied)

**AWS** (eu-west-2; account 919693204623; the GitHub OIDC provider exists):

```bash
# 1. Deploy role for GitHub (production environment of this repository only)
aws iam create-role --role-name LifeInUK-GitHub-BackendDeploy --max-session-duration 3600 \
  --description "GitHub Actions: life-in-uk/backend production releases" \
  --assume-role-policy-document file://infra/aws/backend-deploy-trust.json
aws iam put-role-policy --role-name LifeInUK-GitHub-BackendDeploy --policy-name release \
  --policy-document file://infra/aws/backend-deploy-policy.json
# 2. The only command GitHub may run on the instance
aws ssm create-document --name LifeInUK-BackendRelease --document-type Command --document-format YAML \
  --content file://infra/aws/ssm-backend-release.yml --region eu-west-2
# 3. Instance role: read release archives (adds to the existing inline policy set)
aws iam put-role-policy --role-name LifeInUK-Origin --policy-name LifeInUK-ReleaseRead \
  --policy-document file://infra/aws/instance-role-release-read.json
# 4. Expire release archives after 60 days (keeps the existing backup rule; the call replaces the whole configuration)
aws s3api put-bucket-lifecycle-configuration --bucket lifeinuk-backups-919693204623-eu-west-2 \
  --lifecycle-configuration file://infra/aws/backups-bucket-lifecycle.json
```

The deploy role can upload to `releases/backend/` only (it cannot read or
delete backups), and can send only `LifeInUK-BackendRelease` to
`i-0d0c006308c9ffba0`; the document accepts only `status`, `deploy` and
`rollback` with pattern-checked parameters. `ssm:GetCommandInvocation` has no
resource-level permissions, so the role can read the output of any Run
Command in this account, including other projects' (do not print secrets in
Run Command output anywhere in the account). The instance already has
`AmazonSSMManagedInstanceCore` and its agent is online; nothing on the
instance or its security group changes.

**Server** (operator, over SSH or Session Manager; RUNBOOK.md section 3):
sync the deployment files (this release adds `scripts/release.sh` and changes
`scripts/common.sh` and `scripts/build-image.sh`), then
`install -m 0600 /opt/lifeinuk/env/release.env.example /etc/lifeinuk/release.env`.
Check with `/opt/lifeinuk/scripts/release.sh status`.

**GitHub** (repository settings):

- Environment `production`: required reviewer = owner; deployment branches = `main` only.
- Variables: `AWS_BACKEND_DEPLOY_ROLE_ARN` =
  `arn:aws:iam::919693204623:role/LifeInUK-GitHub-BackendDeploy`,
  `BACKEND_INSTANCE_ID` = `i-0d0c006308c9ffba0`,
  `BACKEND_RELEASE_S3_URI` = `s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend`.
  None of these are secrets; no secrets are stored in GitHub.
- Branch protection on `main`: require the `CI` check.
