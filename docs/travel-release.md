# Travel services: coordinated production release

One release turns on all three travel services: London Underground (TfL), Roads with place search
(National Highways, OS Names) and Rail arrivals and departures (National Rail via the Rail Data
Marketplace). Status before the release, licence notes and limits:
[travel-production-status.md](travel-production-status.md),
[travel-provider-approvals.md](travel-provider-approvals.md).

Every step below needs the owner's go-ahead. Nothing here creates AWS resources, IAM, or paid services.

**Status: prepared, not executed.** Production still runs the "Before" column below (read-only preflight
2026-11-18 21:11 UTC). Record the execution in `/opt/lifeinuk/releases.log` (automatic) and in the
release PR or issue, not by editing this page.

## What changes in production

| | Before | After |
|---|---|---|
| Backend image | `lifeinuk-backend:57f25bf78ef9` | the CI image of release commit **R** (backend `main` with #58, #59 and the set-env change) |
| `/opt/lifeinuk` deploy files | digest `3dd09ff5…` | digest of R (scripts only: backup to S3 verified, `set-env.sh`; compose, nginx, db unchanged) |
| `/etc/lifeinuk/backend.env` | travel off, no provider keys | four keys, departures URL, three switches on (below; Rail last, step 5) |
| Database | Flyway V15 | V15 (no migration). Refresh writes only the two latest-snapshot tables |
| Frontend | `d0b1f48` | frontend `main` **F** (homepage travel panel #27, Rail tool in production builds #28) |

Settings written to `backend.env`:

| Name | Value | Kind |
|---|---|---|
| `NATIONAL_HIGHWAYS_API_KEY` | production subscription key | secret, prompted |
| `OS_NAMES_API_KEY` | OS Data Hub key | secret, prompted |
| `NATIONAL_RAIL_API_KEY` | RDM Arrival board product key | secret, prompted |
| `NATIONAL_RAIL_DEPARTURES_API_KEY` | RDM Departure board product key | secret, prompted |
| `NATIONAL_RAIL_DEPARTURES_BASE_URL` | `https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS` | setting |
| `TRAVEL_REFRESH_UNDERGROUND_ENABLED` | `true` (every 10 min, 1 TfL request) | setting |
| `TRAVEL_REFRESH_ROADS_ENABLED` | `true` (every 10 min, ≤ 8 paced requests) | setting |
| `RAIL_ACCESS_ENABLED` | `false` until the new image runs (step 2), then `true` (step 5): per request, 6 per viewer and 60 per instance per minute | setting |

The Arrival base URL, the National Highways and OS Names base URLs and both refresh intervals keep
their reviewed defaults (`application.yml`).

## Moving the keys (no new subscription, no secret in Git, logs or command history)

The four keys already exist in the owner's local backend environment (`~/.config/life-in-uk/backend-8188.env`
and the backend's git-ignored `.env`; both 0600, identical). They go to the server through an
**interactive Session Manager session** (AWS console → Systems Manager → Session Manager → Start
session, instance `i-0d0c006308c9ffba0`). This account has no Session Manager logging preferences
(`SSM-SessionManagerRunShell` does not exist), so session input is not recorded. Run Command is not
used for secrets: its parameters stay in the command history. SSH is closed (no port 22 rule).

`scripts/set-env.sh` prompts for each secret with hidden input, saves the old file to
`/root/lifeinuk-env-backups/`, replaces lines atomically (0600) and prints only names and
`sha256:<first 8 hex>` fingerprints. The workstation prints the same fingerprints from the local copy:

```bash
deploy/ops/env-fingerprint.sh ~/.config/life-in-uk/backend-8188.env \
  NATIONAL_HIGHWAYS_API_KEY OS_NAMES_API_KEY NATIONAL_RAIL_API_KEY NATIONAL_RAIL_DEPARTURES_API_KEY
```

Expected (2026-11-18): `48051946`, `b20fe8c3`, `9036a319`, `89c8c1a7`. Each key was checked live
through the local backend on 2026-11-18 (Rail arrivals and departures for KGX, place search, TfL and
National Highways refresh).

## Why Rail is switched on last

`backend.env` is read only when the app container is **created**: `set-env.sh` changes nothing in the
running app, and neither does a container restart after a crash or a reboot (tested in
`deploy/test/ci-stack-test.sh`). But a failed release puts the previous image back by *recreating* the
container, and that reads the file as it is. If Rail were already on in the file, a failed release would
start Rail on 57f25bf, which keeps one shared per-viewer budget for everybody (fixed in #59). So the file
keeps `RAIL_ACCESS_ENABLED=false` through the release, and Rail is switched on only once the new image is
confirmed (step 5). Underground and Roads may be on during the release: 57f25bf has the same refresh code.

## Release values

| | |
|---|---|
| Backend release commit R | `6452d648b1b34f5310bdd63edf660e1164194f3f` (#60 merge; CI run 44398127675) |
| Frontend release commit F | `84495f85fd0ab2b6572f64a2846d635b6901b0a6` (#28 merge; CI run 44395200484 passed) |
| Deploy-files digest, old → new | `3dd09ff5df0e191501c3a0072c27170d78f14498345bbdc202120ff0fd496b78` → `33c07651652bf33a15f93c555866ff4564cd73bb25c84b0b922fca6dc1f64efb` |
| `deploy-files.tar.gz` of R (reproducible) | sha256 `2fa2c29193f24fa8b68eac788b967477cd017222275beeb0d72397a3fe0d357a` |
| `update-deploy-files.sh` of R | sha256 `6322582a7f5b91bc82d8a9bf7709a6edfaca48612911345671c7be2907221d2e` |
| Image R | `lifeinuk-backend:6452d648b1b3` (no migration: Flyway stays V15) |
| Rollback image | `lifeinuk-backend:57f25bf78ef9` (on the server; becomes `rollback_image` after step 4) |
| Frontend rollback source | Deploy production run `44336471717` (artifact `production-site`, kept until 2027-02-16) |

Later documentation-only commits on `main` do not change any of these: the deploy workflow takes R by SHA.

## Procedure

Not between 02:30 and 02:50 UTC (nightly backup). About 40 minutes. The API is unavailable while the app
container is recreated, twice (steps 4 and 5; under a minute each, about 5 s of 502s in the isolated
rehearsal); static pages are never affected.

**Stop rule:** if a step's expected result does not appear, stop there and use the rollback row for that
step. Do not continue to the next step.

1. **Workstation preparation.** Check: backend CI for R and frontend CI for F passed, and R's run kept the
   `backend-image` artifact. Stage and upload the deploy files:

   ```bash
   deploy/ops/stage-deploy-files.sh 6452d648b1b34f5310bdd63edf660e1164194f3f /tmp/deploy-files   # archive_sha256 and digest as above
   P=s3://lifeinuk-backups-919693204623-eu-west-2/releases/backend/deploy-files/6452d648b1b34f5310bdd63edf660e1164194f3f
   aws s3 cp /tmp/deploy-files/deploy-files.tar.gz $P/ && aws s3 cp deploy/ops/update-deploy-files.sh $P/   # from a checkout of R
   ```

   Stop if either checksum differs from the table.

2. **Deploy files, no restart** ([deploy-files-update.md](deploy-files-update.md), step 2 command) with the
   two digests and `s3://lifeinuk-backups-919693204623-eu-west-2/postgres/`. Expect `RESULT updated
   digest=3dd09ff5…->33c07651… image=lifeinuk-backend:57f25bf78ef9 flyway=15
   backup_s3_uri=s3://lifeinuk-backups-919693204623-eu-west-2/postgres/`. Stop on `RESULT refused` (nothing
   changed) or `RESULT restored` (old files back). Then GitHub **Deploy production** `action` = status:
   the new digest, `app_health=healthy`, `backup_s3_uri=s3://…/postgres/`.

3. **Configuration, no restart.** Session Manager session (AWS console):

   ```bash
   sudo -i
   /opt/lifeinuk/scripts/set-env.sh /etc/lifeinuk/backend.env \
     NATIONAL_HIGHWAYS_API_KEY OS_NAMES_API_KEY NATIONAL_RAIL_API_KEY NATIONAL_RAIL_DEPARTURES_API_KEY \
     NATIONAL_RAIL_DEPARTURES_BASE_URL=https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS \
     TRAVEL_REFRESH_UNDERGROUND_ENABLED=true TRAVEL_REFRESH_ROADS_ENABLED=true RAIL_ACCESS_ENABLED=false
   ```

   Paste each key at its prompt. Expect the four fingerprints `48051946`, `b20fe8c3`, `9036a319`,
   `89c8c1a7` and `RAIL_ACCESS_ENABLED=false`; for a wrong fingerprint run the command again with that
   name only. Note the printed backup path (`/root/lifeinuk-env-backups/backend.env.<time>`). The running
   app is unchanged. Continue with step 4 in the same sitting.

4. **Backend release.** GitHub **Deploy production**: `action` = deploy,
   `ref` = `6452d648b1b34f5310bdd63edf660e1164194f3f`, `approve_migrations` = `none`; approve the
   `production` environment. The server takes the pre-release dump and its S3 copy (SHA-256 verified by
   S3; refuses with nothing changed otherwise), then recreates the app with image R and the new
   `backend.env`. Expect the run summary `deployed image=lifeinuk-backend:6452d648b1b3 … flyway=15->15
   … backup=life_in_uk-<time>-pre-release-6452d648b1b3.dump` and a passing public smoke test. If the job
   fails, the server has already put 57f25bf back, with Rail still off: stop and read the run log.

5. **Rail on** (Session Manager, only after step 4 succeeded):

   ```bash
   /opt/lifeinuk/scripts/release.sh status | grep -E '^(current_image|app_health)='   # must be 6452d648b1b3 and healthy
   /opt/lifeinuk/scripts/set-env.sh /etc/lifeinuk/backend.env RAIL_ACCESS_ENABLED=true
   cd /opt/lifeinuk && docker compose up -d --no-deps app
   docker compose ps app          # wait for (healthy)
   ```

   Stop (and skip this step) if `current_image` is not `lifeinuk-backend:6452d648b1b3`. If the app does
   not become healthy within 3 minutes: `set-env.sh … RAIL_ACCESS_ENABLED=false` and
   `docker compose up -d --no-deps app` again.

6. **Backend checks** (workstation, through CloudFront):

   ```bash
   curl -s https://lifeinuk.info/api/travel/underground | jq '.observedAt, (.lines|length)'     # 200, observed within ~1 min of step 4, 11 lines
   curl -s 'https://lifeinuk.info/api/travel/roads?lat=51.5074&lon=-0.1278' | jq .snapshotAt    # 200, within ~2 min of step 4
   curl -s 'https://lifeinuk.info/api/places/search?q=Leeds' | jq '.places|length'             # 200, places found
   curl -s 'https://lifeinuk.info/api/rail/stations/KGX/disruptions?direction=arrivals' | jq .generatedAt
   curl -s 'https://lifeinuk.info/api/rail/stations/KGX/disruptions?direction=departures' | jq .generatedAt
   sleep 60; for i in $(seq 7); do curl -s -o /dev/null -w '%{http_code} ' 'https://lifeinuk.info/api/rail/stations/LDS/disruptions'; done   # 200 ×6, then 429
   ```

   Server: `cd /opt/lifeinuk && docker compose logs app | grep -E 'refresh (succeeded|failed|did not start)'`
   shows Underground and Roads `succeeded`, and no log line contains a key (prints a count, never a key):
   `docker compose logs app | grep -c -F -f <(sed -n 's/^[A-Z_]*_API_KEY=//p' /etc/lifeinuk/backend.env)` is 0. A service that fails here: turn its switch off (rollback table) and continue with the others.

7. **Frontend release.** GitHub **Deploy production** (frontend): `ref` = F, `preflight` off, approve.
   Then `npm run verify:site -- --base https://lifeinuk.info --production` from F.

8. **Site checks**, phone and desktop, `/zh-CN/`, `/zh-TW/`, `/en/`, `/ur/`, `/hi/`: the travel panel sits
   directly under the search; Underground shows the line summary and "updated <time> (UK time)"; Roads finds
   a place by name and lists nearby closures (or none nearby); Rail shows a board in both directions.

9. **Watch.** Refresh lines every 10 minutes for the first half hour; Underground and Roads `observedAt`
   / `snapshotAt` stay under 15 minutes old. The nightly backup at 02:40 UTC:
   `journalctl -u lifeinuk-backup --since today` shows `copied to s3://…/postgres/… (SHA-256 … verified by S3)`.

## Rollback

Take the smallest step that fixes the problem. None needs a database restore: there is no migration, and
the refresh only replaces the latest-snapshot rows. Server commands run as root in `/opt/lifeinuk`.

| Problem | Commands | Effect |
|---|---|---|
| Step 2 failed | none: `RESULT refused` changed nothing, `RESULT restored` put the old files back | |
| One service misbehaves (errors, cost, provider request) | `scripts/set-env.sh /etc/lifeinuk/backend.env TRAVEL_REFRESH_ROADS_ENABLED=false` (or `TRAVEL_REFRESH_UNDERGROUND_ENABLED=false`, `RAIL_ACCESS_ENABLED=false`); `docker compose up -d --no-deps app` | that service stops calling its provider. A snapshot turns stale and the site stops showing it within 15 minutes; Rail answers `RAIL_DISABLED` and the tool says so |
| Wrong key | `scripts/set-env.sh /etc/lifeinuk/backend.env <NAME>` (prompted); `docker compose up -d --no-deps app` | |
| All of the configuration | `ls -t /root/lifeinuk-env-backups/` (the oldest `backend.env.<time>` of this release is the pre-release file, printed in step 3); `cp -p /root/lifeinuk-env-backups/backend.env.<time> /etc/lifeinuk/backend.env`; `docker compose up -d --no-deps app` | exactly the pre-release settings: all travel off, no keys |
| The new image | first `scripts/set-env.sh /etc/lifeinuk/backend.env RAIL_ACCESS_ENABLED=false` (57f25bf lacks the per-viewer fix), then **Deploy production** `action` = rollback, `allow_newer_schema` off | 57f25bf with Underground and Roads as configured, Rail off |
| Frontend | **Deploy production** (frontend) with `rollback_run_id` = `44336471717` | the site returns to `d0b1f48` (before the homepage panel) |
| Deploy files | not needed: the new scripts keep the old behaviour. If ever: `update-deploy-files.sh` with the two digests swapped (deploy-files-update.md, "Undo") | |

Database restore (`restore.sh replace`) is not part of any travel rollback: it would discard everything
written since the dump and fixes nothing here (RUNBOOK.md section 3, "Application rollback and database
restore are different operations").
