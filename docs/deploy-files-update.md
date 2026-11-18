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
  recreates a container (such changes need a planned `docker compose up`);
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
