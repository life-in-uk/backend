# Life in UK production runbook

One EC2 instance (eu-west-2, Ubuntu 24.04, t3.large) runs the backend with
Docker Compose; the frontend is S3 + CloudFront (frontend repository,
`docs/deploy-cloudfront.md`). Everything here is operated by one person over
SSH. Content publishing has its own document: [PUBLISHING.md](PUBLISHING.md).

```
CloudFront ──HTTPS (SG: CloudFront prefix list only)──► :443 proxy (nginx) ──► app (Spring Boot) ──► db (PostgreSQL 16)
                                                                              ▲ 127.0.0.1:8080 (operator SSH tunnel only)
```

| Item | Where |
|---|---|
| Compose project | `/opt/lifeinuk/compose.yml`, `/opt/lifeinuk/.env` (`APP_IMAGE=lifeinuk-backend:<commit>`), `nginx/`, `db/`, `scripts/` (copies of `deploy/`) |
| Secrets | `/etc/lifeinuk/{db,backend,proxy,backup}.env`, root 0600, from `deploy/env/*.example`; master copies in the owner's password manager |
| Database | Docker volume `lifeinuk_pgdata`; roles `postgres` (operator scripts only) and `life_in_uk_app` (the app, owner of the schema) |
| Backups | `/var/backups/lifeinuk` (root 0700, mounted at `/backups` in db) + S3 `BACKUP_S3_URI` + daily EBS snapshots |
| Origin certificate | `/etc/letsencrypt` (Let's Encrypt, DNS-01 via Route 53, instance role) |
| Timers | `lifeinuk-backup.timer` (02:30 UTC), `lifeinuk-cert-renew.timer` (twice daily) |
| Memory | db 1 GiB, app 1.5 GiB (heap ≤ 70 %), proxy 128 MiB; 2 GiB swap |

Fixed rules: ports 5432 and 8080 are never opened to the network; the
`local` Spring profile and `LIFE_IN_UK_EDITORIAL_LOCAL_REVIEW_TOKEN` are never
set in production; editorial credentials stay unset unless PUBLISHING.md's
setup has been approved; Rail stays disabled and travel refresh stays off
until the owner approves it (`backend.env`).

Commands below run on the server as root (`sudo -i`) in `/opt/lifeinuk`
unless marked *workstation*. `docker compose` there means the project above.

## 1. Server preparation (once, after the AWS resources exist)

```bash
# Docker Engine + Compose plugin from Docker's apt repository
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list
apt-get update && apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
snap install aws-cli --classic                      # backups to S3 (instance role credentials)

# 2 GiB swap as a safety margin
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

install -d -m 0755 /opt/lifeinuk
install -d -m 0700 /etc/lifeinuk /var/backups/lifeinuk
```

*Workstation*: copy the deployment files (they contain no secrets):

```bash
rsync -a --delete deploy/compose.yml deploy/nginx deploy/db deploy/scripts deploy/systemd deploy/env ubuntu@<host>:/tmp/lifeinuk/
```

Server:

```bash
rsync -a /tmp/lifeinuk/ /opt/lifeinuk/ && chown -R root:root /opt/lifeinuk && rm -rf /tmp/lifeinuk
for f in db backend proxy backup; do install -m 0600 /opt/lifeinuk/env/$f.env.example /etc/lifeinuk/$f.env; done
# Fill the values (openssl rand -hex 32 for each secret). APP_DB_PASSWORD = DB_PASSWORD.
# ORIGIN_VERIFY_SECRET is also entered as the CloudFront origin custom header.
editor /etc/lifeinuk/db.env /etc/lifeinuk/backend.env /etc/lifeinuk/proxy.env /etc/lifeinuk/backup.env

# Origin certificate (needs DNS origin.lifeinuk.info and the instance role's Route 53 permission)
docker compose --profile tools run --rm certbot certonly --dns-route53 -d origin.lifeinuk.info \
  --agree-tos -m <owner contact address> --non-interactive

cp /opt/lifeinuk/systemd/*.service /opt/lifeinuk/systemd/*.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now lifeinuk-backup.timer lifeinuk-cert-renew.timer
```

## 2. First deployment

*Workstation* (both repositories committed):

```bash
deploy/scripts/build-image.sh                     # mvn verify + image lifeinuk-backend:<commit> + archive
deploy/seed/export-approved-seed.sh               # approved public data only; proves it (see the script header)
cat ~/.local/state/life-in-uk/production-seed/<time>/compare.txt   # must say "differences: 0"
scp ~/.local/state/life-in-uk/release-images/lifeinuk-backend-<commit>.tar.gz* \
    ~/.local/state/life-in-uk/production-seed/<time>/seed.dump* ubuntu@<host>:/tmp/
```

Make the seed right before deploying, from the content that should go live.
It contains published and superseded (approved) Guides with their editorial
history, source configuration and the Bank Holidays evidence; it never
contains the n8n schema, unapproved drafts or travel provider data.

Server:

```bash
cd /tmp && sha256sum -c lifeinuk-backend-<commit>.tar.gz.sha256 && gunzip -c lifeinuk-backend-<commit>.tar.gz | docker load
mv /tmp/seed.dump /tmp/seed.dump.sha256 /var/backups/lifeinuk/
cd /opt/lifeinuk && echo 'APP_IMAGE=lifeinuk-backend:<commit>' > .env
docker compose up -d db
scripts/seed-restore.sh /var/backups/lifeinuk/seed.dump    # refuses unless the database is new and empty
docker compose up -d app && docker compose logs app | grep -E 'validated|up to date'   # Flyway: validated 15, no migration
docker compose up -d proxy
scripts/backup.sh && scripts/restore.sh check /var/backups/lifeinuk/<that backup>
```

### Verify the first deployment

*Workstation*, through an SSH tunnel to the app (never through a public port):

```bash
ssh -N -L 18080:127.0.0.1:8080 ubuntu@<host> &
python3 deploy/seed/compare-api.py http://127.0.0.1:8188 http://127.0.0.1:18080 --skip /sitemap.xml --skip /robots.txt
curl -s http://127.0.0.1:18080/robots.txt          # Sitemap: https://lifeinuk.info/sitemap.xml
```

Then the frontend (frontend repository, `docs/deploy-cloudfront.md`):
`build:release`, `deploy:site --execute`, and before DNS points at
CloudFront, `verify:site --base https://lifeinuk.info --connect <edge IP> --production`.
After the DNS change, `verify:site --base https://lifeinuk.info --production`.

## 3. Backend release

Normally from GitHub Actions (**Deploy production**, see `docs/ci-cd.md`): it
ships the image CI tested and runs `scripts/release.sh deploy` here through
SSM (pre-release backup, switch, health check, automatic return to the
previous image). `scripts/release.sh status|rollback` work the same by hand.
The audit trail is `/opt/lifeinuk/releases.log`.

**Deployment files.** When `deploy/compose.yml`, `nginx/`, `db/`, `scripts/` or
`systemd/` change, sync them before the release (the workflow refuses until
the server's copy matches the release commit):

```bash
rsync -a --delete deploy/compose.yml deploy/nginx deploy/db deploy/scripts deploy/systemd deploy/env ubuntu@<host>:/tmp/lifeinuk/   # workstation, at the release commit
rsync -a /tmp/lifeinuk/ /opt/lifeinuk/ && chown -R root:root /opt/lifeinuk && rm -rf /tmp/lifeinuk                                 # server
docker compose up -d                       # only if compose.yml, nginx or db changed; take scripts/backup.sh first
cp /opt/lifeinuk/systemd/*.service /opt/lifeinuk/systemd/*.timer /etc/systemd/system/ && systemctl daemon-reload   # only if systemd changed
```

Manual release (fallback when GitHub or SSM is unavailable):

```bash
deploy/scripts/build-image.sh                       # workstation; then copy and load the archive as above
scripts/backup.sh pre-release                       # server
sed -i 's/^APP_IMAGE=.*/APP_IMAGE=lifeinuk-backend:<new commit>/' .env
docker compose up -d app && docker compose ps       # wait for (healthy); static pages stay up meanwhile
```

Rollback: put the previous tag back in `.env` and `docker compose up -d app`.
If the new release applied a Flyway migration, the old image will refuse the
newer schema: restore the `pre-release` backup first
(`scripts/restore.sh replace … --confirm-replace-production`), then start the
old image. Keep the previous two images (`docker image ls lifeinuk-backend`);
remove older ones with `docker image rm`.

Image updates (PostgreSQL, nginx, Temurin, certbot) are made in the
repository: change the pinned `tag@sha256` in `compose.yml`/`Dockerfile`,
run `deploy/test/stack-test.sh`, commit, then deploy as above
(`docker compose pull db proxy && docker compose up -d`). Take a backup first.

## 4. Backups and restore

- Nightly `scripts/backup.sh`: custom-format dump of `life_in_uk`, checked
  (`pg_restore --list` must show the Guide tables), SHA-256, copied to S3
  (`BACKUP_S3_URI`, SSE-S3; retention by the bucket's lifecycle rule), local
  copies kept 14 days. `journalctl -u lifeinuk-backup` shows each run.
- Daily EBS snapshots (Data Lifecycle Manager, 7 days) cover the whole disk.
- **Monthly drill**: `scripts/restore.sh check /var/backups/lifeinuk/<latest>.dump`
  restores into a scratch database, prints Guide/publication/Flyway counts and drops it.
- **Restore production**: `scripts/restore.sh replace <dump> --confirm-replace-production`
  takes a fresh backup, stops the app, keeps the current database as
  `life_in_uk_before_<time>`, restores, starts the app and waits for health.
  If the restore fails, the previous database is put back.
- **Instance lost**: new instance → section 1 → copy the latest dump from S3
  into `/var/backups/lifeinuk` → `docker compose up -d db` →
  `scripts/restore.sh replace <dump> --confirm-replace-production` →
  `docker compose up -d proxy` → re-point `origin.lifeinuk.info` if the IP
  changed. Alternatively create the volume from the latest EBS snapshot.
- Deliberately not backed up: the env files (password manager) and the
  certificate (re-issued by certbot).

## 5. Secrets

| Secret | Rotate by |
|---|---|
| `APP_DB_PASSWORD` / `DB_PASSWORD` | `docker compose exec db psql -U postgres -c "ALTER ROLE life_in_uk_app PASSWORD '<new>'"`, update both env files, `docker compose up -d app` |
| `POSTGRES_PASSWORD` | `ALTER ROLE postgres PASSWORD '<new>'`, update `db.env` (only read at initialisation) |
| `ORIGIN_VERIFY_SECRET` | update `proxy.env`, `docker compose up -d proxy`, then the CloudFront origin custom header; API answers 403 for the minute in between (static pages unaffected) |
| Provider keys | update `backend.env`, `docker compose up -d app` |

## 6. Routine maintenance

- Weekly glance: `docker compose ps`, `df -h /`, `docker system df`, `journalctl -u lifeinuk-backup --since -7d`.
- Monthly: `apt-get update && apt-get upgrade`, reboot if `/var/run/reboot-required`
  (all services restart by themselves; Ubuntu security updates also install unattended),
  restore drill, prune old images.
- Certificate: renewed automatically; `docker compose --profile tools run --rm certbot certificates` shows expiry.
- Logs: `docker compose logs --since 1h app|proxy|db` (rotated at 5 × 10 MB per container).
