# Updating the server's deployment files without a release

`release.sh deploy` refuses unless the server's deploy-files digest
(`compose.yml`, `nginx/`, `db/`, `scripts/`, `systemd/` under `/opt/lifeinuk`)
equals the release commit's. When those files change, the server copy is
updated first with `deploy/ops/update-deploy-files.sh`. The GitHub guard stays
on throughout: until the update, every release of the new commit is refused.

The script:

- takes the release lock, so no release can run during the update;
- refuses unless the server digest is the expected old one, the archive has
  the expected SHA-256 and unpacks to the expected new digest, and holds only
  deploy files;
- refuses if `compose.yml`, `nginx/` or `db/` change: it never restarts or
  recreates a container. For nginx template changes use
  `deploy/ops/update-nginx-files.sh` ([nginx changes](#nginx-changes));
- refuses if the app is not healthy, or the AWS CLI is older than 2.9;
- with a `BACKUP_S3_URI` argument, checks it equals the nightly unit's value
  (`systemctl show lifeinuk-backup.service`), then writes it to
  `/etc/lifeinuk/backup.env` (0600, other lines kept);
- saves the current files and `backup.env` to
  `/root/lifeinuk-deploy-files-before-<time>` (0700) and checks the copy's digest;
- after installing, checks the new digest, that `release.sh status` reports
  the same image, image ID, Flyway version, rollback target and a healthy app,
  that the backup location is seen, and that the containers are the same
  (IDs, start times, restart counts);
- on any failure after saving, puts the saved files back, checks the old
  digest again and exits nonzero (`RESULT restored …`).

It never runs a release, touches the database, the other env files, `.env`,
`releases.log` or the systemd units in `/etc/systemd/system`.

Tested (`ubuntu:24.04` as root with docker/systemctl/aws stubs, the real
`/opt/lifeinuk` and `/etc/lifeinuk` layout, archive of main 23ae920 →
production digest `3dd09ff5…`): 10 refusals leave everything unchanged, a
failure after install and a container change after install both restore the
old files, the update succeeds, and a rerun is refused.

## Procedure (each step needs the owner's approval)

Values for the backup-safety change (PR for `backup-safety`); recompute the
new digest and archive SHA-256 at the merge commit:

| | |
|---|---|
| Old digest (production, 2026-11-18) | `3dd09ff5df0e191501c3a0072c27170d78f14498345bbdc202120ff0fd496b78` |
| New digest (branch head) | `936d3bc259824469f69577906f1643c5b2848e2a15e6418d3b367dc8f8cf2761` |
| Changes on the server | `scripts/` (backup.sh, common.sh, release.sh; renew-cert.sh removed), `systemd/` (unused lifeinuk-cert-renew.* removed), new `certbot/`, `env/backup.env.example` |
| Unchanged | `compose.yml`, `nginx/`, `db/`, every container, `/etc/systemd/system` |
| `BACKUP_S3_URI` | `s3://lifeinuk-backups-919693204623-eu-west-2/postgres/` (already in `backup.env`, checked 2026-11-18; the nightly unit reads the same file) |

Not between 02:30 and 02:50 UTC (nightly backup).

1. *Workstation*, at the merge commit:

   ```bash
   deploy/ops/stage-deploy-files.sh <merge commit> /tmp/deploy-files    # prints archive_sha256 and deploy_files_digest
   sha256sum deploy/ops/update-deploy-files.sh
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/<merge commit>
   aws s3 cp /tmp/deploy-files/deploy-files.tar.gz $P/ && aws s3 cp deploy/ops/update-deploy-files.sh $P/
   ```

   The instance role can already read `releases/backend/*` (60-day lifecycle); no IAM change.

2. *Workstation*, owner credentials: one `AWS-RunShellScript` command to the
   instance (its shell is dash, so the script runs with bash):

   ```sh
   set -u
   d=$(mktemp -d)
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/<merge commit>
   aws s3 cp --only-show-errors $P/update-deploy-files.sh $d/ && aws s3 cp --only-show-errors $P/deploy-files.tar.gz $d/ \
     && echo "<script sha256>  $d/update-deploy-files.sh" | sha256sum -c --quiet \
     && bash $d/update-deploy-files.sh $d/deploy-files.tar.gz <archive sha256> \
          3dd09ff5df0e191501c3a0072c27170d78f14498345bbdc202120ff0fd496b78 <new digest> \
          s3://lifeinuk-backups-919693204623-eu-west-2/postgres/
   rc=$?; rm -rf "$d"; exit $rc
   ```

   Expect `RESULT updated digest=3dd09ff5…-><new> image=lifeinuk-backend:57f25bf78ef9 flyway=15 backup_s3_uri=s3://…/postgres/`.
   `RESULT refused …` means nothing changed; `RESULT restored …` means the
   old files are back.

3. GitHub: **Deploy production**, `action` = status (read-only). Expect the new
   `deploy_files_digest`, `current_image=lifeinuk-backend:57f25bf78ef9`,
   `flyway_version=15`, `app_health=healthy` and `backup_s3_uri=s3://…/postgres/`.

4. Optional, separately approved: prove the verified upload from the
   instance. One command: `/opt/lifeinuk/scripts/backup.sh upload-check`
   (reads the database; writes one dump and its `.sha256` to `postgres/`,
   expiring after 30 days). Then *workstation*: "Check an S3 copy" in
   RUNBOOK.md section 4 (S3's stored SHA-256 equals the `.sha256`, the
   download restores). A negative probe from the instance,
   `aws s3api put-object --bucket … --key postgres/probe-baddigest --body /etc/hostname --checksum-sha256 <SHA-256 of other bytes>`,
   must fail with BadDigest and leave no object (check with `head-object`
   from the workstation).

5. The next nightly run (02:30 UTC): `journalctl -u lifeinuk-backup --since today`
   shows `copied to s3://…/postgres/life_in_uk-<time>.dump (SHA-256 … verified by S3)`.

**Undo** (no release involved): run the same command with the old archive
(stage main 23ae920), its SHA-256, the new digest as old and
`3dd09ff5…` as new, without the URI argument; or copy back
`/root/lifeinuk-deploy-files-before-<time>`. `BACKUP_S3_URI` in `backup.env`
is harmless for the old scripts (the nightly unit sets the same value).

## nginx changes

`update-deploy-files.sh` refuses nginx changes. It also replaces whole
directories (`rm -rf` + `mv`), and the running proxy bind-mounts
`/opt/lifeinuk/nginx/templates` by directory. After such a swap the container
still sees the removed directory. Production showed this on 2026-11-20: the
proxy, started 2026-11-17 23:12, sees an empty `/etc/nginx/templates`. It keeps
serving the configuration it rendered at start; a recreate mounts the current
directory again.

`deploy/ops/update-nginx-files.sh` covers a change limited to `nginx/` (plus
the `env/*.example` reference copies):

- `install <archive> <sha256> <old digest> <new digest>`: the same lock,
  digest and archive checks as `update-deploy-files.sh`. It lists every file
  that would be added or replaced and every server file the archive lacks. It
  refuses unless all changes are under `nginx/` or `env/*.example`, at least
  one is under `nginx/`, and nothing would have to be deleted (it never
  deletes). It validates the staged templates with `nginx -t` in a throwaway
  container. That container uses the running proxy's image, `proxy.env` and
  certificates, and has no network, no published port, read-only mounts and
  `--rm`. The script records `release.sh status` and the containers, and saves
  each file it replaces to `/root/lifeinuk-nginx-files-before-<time>/` (0700,
  with a `MANIFEST`). It then installs file by file (temporary file in the same
  directory, rename, root 0644) and checks the new digest, an unchanged image,
  Flyway version, rollback target, a healthy app and unchanged containers. Any
  failure puts the saved files back (`RESULT restored …`).
- `validate [<templates dir>]`: read-only `nginx -t` of the installed (or
  given) templates, the same way.

It never restarts or recreates a container, and never touches `.env`,
`releases.log`, `editorial-ci/`, `seed/`, `test/`, `/etc/lifeinuk`,
`/etc/letsencrypt` or systemd. The new configuration takes effect only when
the proxy is recreated, which is a separate approved step:
`docker compose up -d --force-recreate --no-deps proxy`. A plain
`docker compose up -d` keeps the running proxy, because compose does not
watch the content of a bind-mounted directory.

Tested locally on 2026-11-20 against a production-shaped tree. The tree held
the `e4503f9` deploy files (digest `535795c0…`) plus production's other
files. The test used the `a923f58` archive, the production nginx image
(`nginx:1.28-alpine@sha256:a8b39bd9…`), a stand-in running proxy with the
compose labels and a stub `release.sh status`. Results:

- 21 checks passed.
- 8 refusals left the tree unchanged: wrong checksum, wrong old digest, wrong
  new digest, broken template (`nginx -t`), a `scripts/` change, a deletion,
  no nginx change, and an unhealthy app.
- A failure after install restored the old files.
- The install changed exactly two files. The stand-in proxy kept the same ID,
  start time, PID and rendered configuration.
- A rerun was refused, and copying back the saved files restored the old
  digest.

With Compose 5.5.1, `up -d --no-deps proxy` kept the old container and
configuration. `up -d --force-recreate --no-deps proxy` rendered the two
collector locations without touching the app. Recreating again with the old
template removed them.

### Stock index collector route (`a923f58`)

Production inventory (read-only, 2026-11-20): image `e4503f9f2892`, Flyway
16, digest `535795c0ff45aa11a22d2c122b07417bedafa6e8ed9660cb43ac3189bfd4b05c`.

| File under `/opt/lifeinuk` | Change |
|---|---|
| `nginx/templates/lifeinuk.conf.template` | replaced (`34b5cdc8…` → `f0f86d31…`; the two collector locations, rate-limit zone, 413 handler) |
| `env/backend.env.example` | replaced (reference copy only; never read by a container) |
| `compose.yml`, `db/`, `scripts/`, `systemd/`, `certbot/`, other `env/*.example` (16 files) | identical, untouched |
| `.env`, `releases.log`, `RUNBOOK.md`, `PUBLISHING.md`, `editorial-ci/`, `seed/`, `test/` (11 files, not deploy files) | untouched |
| deleted | none |

| | |
|---|---|
| Archive (`stage-deploy-files.sh a923f58…`) SHA-256 | `cde6d2b9b3b87a171154082d3f62f8e200dd89f1fbefe58dfa348c4e7c263b25` |
| Old digest | `535795c0ff45aa11a22d2c122b07417bedafa6e8ed9660cb43ac3189bfd4b05c` |
| New digest | `b126b2b4644d3af906756bc1395dc5f8557895f28856f8e1cbbba8fb5f5d608b` |

Not between 02:30 and 02:50 UTC. Each step needs the owner's approval.

1. *Workstation*, at the merge commit of this procedure (the script is not a
   deploy file, so the archive of `a923f58` is unchanged):

   ```bash
   deploy/ops/stage-deploy-files.sh a923f58da02ea1b2ea521512da2005203618e333 /tmp/deploy-files   # expect the archive SHA-256 above
   sha256sum deploy/ops/update-nginx-files.sh
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/a923f58da02ea1b2ea521512da2005203618e333
   aws s3 cp /tmp/deploy-files/deploy-files.tar.gz $P/ && aws s3 cp deploy/ops/update-nginx-files.sh $P/
   ```

2. *Server* (one `AWS-RunShellScript` command; dash, so bash runs the script).
   Installs the two files. The proxy keeps serving the old configuration.

   ```sh
   set -u
   d=$(mktemp -d)
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/a923f58da02ea1b2ea521512da2005203618e333
   aws s3 cp --only-show-errors $P/update-nginx-files.sh $d/ && aws s3 cp --only-show-errors $P/deploy-files.tar.gz $d/ \
     && echo "<script sha256>  $d/update-nginx-files.sh" | sha256sum -c --quiet \
     && bash $d/update-nginx-files.sh install $d/deploy-files.tar.gz \
          cde6d2b9b3b87a171154082d3f62f8e200dd89f1fbefe58dfa348c4e7c263b25 \
          535795c0ff45aa11a22d2c122b07417bedafa6e8ed9660cb43ac3189bfd4b05c \
          b126b2b4644d3af906756bc1395dc5f8557895f28856f8e1cbbba8fb5f5d608b
   rc=$?; rm -rf "$d"; exit $rc
   ```

   Expect the plan lines (2 × `replace`, no `server-only`) on stderr and
   `RESULT updated digest=535795c0…->b126b2b4… files=replace:env/backend.env.example,replace:nginx/templates/lifeinuk.conf.template image=lifeinuk-backend:e4503f9f2892 flyway=16 saved=/root/lifeinuk-nginx-files-before-<time> …`.

3. GitHub: **Deploy production**, `action=deploy`,
   `ref=a923f58da02ea1b2ea521512da2005203618e333`, `approve_migrations=17`.
   The workflow does not touch the proxy.

4. *Server*: validate the installed templates, then recreate the proxy (a few
   seconds of 502 on the API through CloudFront; static pages unaffected):

   ```sh
   set -u
   d=$(mktemp -d)
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/a923f58da02ea1b2ea521512da2005203618e333
   aws s3 cp --only-show-errors $P/update-nginx-files.sh $d/ \
     && echo "<script sha256>  $d/update-nginx-files.sh" | sha256sum -c --quiet \
     && bash $d/update-nginx-files.sh validate \
     && docker compose -f /opt/lifeinuk/compose.yml up -d --force-recreate --no-deps proxy \
     && sleep 3 && docker ps --filter name=lifeinuk- --format '{{.Names}} {{.Status}}' \
     && docker exec lifeinuk-proxy-1 grep -c 'location = /api/internal/market-indices/' /etc/nginx/conf.d/lifeinuk.conf
   rc=$?; rm -rf "$d"; exit $rc
   ```

   Expect `RESULT valid`, a new proxy container, and `2`. Then, from anywhere:
   `GET https://lifeinuk.info/api/guides` returns 200, and `GET …/api/internal/market-indices/heartbeat`
   returns 403 `{"code":"ORIGIN_FORBIDDEN"}`. Before the recreate it returns 405 from the new app (404 before step 3).

**Undo.**

- Files only (before step 4): copy back each `replace` line of
  `/root/lifeinuk-nginx-files-before-<time>/MANIFEST`:

  ```sh
  s=/root/lifeinuk-nginx-files-before-<time>
  while read -r kind f mode; do [ "$kind" = replace ] && install -m "$mode" -o root -g root "$s/$f" "/opt/lifeinuk/$f"; done < "$s/MANIFEST"
  /opt/lifeinuk/scripts/release.sh status      # deploy_files_digest=535795c0…
  ```

- After step 4: the same copy-back, then
  `docker compose -f /opt/lifeinuk/compose.yml up -d --force-recreate --no-deps proxy`.
- The app is rolled back separately with **Deploy production** `action=rollback`.
  Rollback checks no deploy-files digest, so it works with either template.
  `allow_newer_schema=true` is needed after V17, because the previous image
  knows only V16; V17 only adds tables (docs/market-indices.md).
