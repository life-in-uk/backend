# Travel services: coordinated production release

One release turns on all three travel services: London Underground (TfL), Roads with place search
(National Highways, OS Names) and Rail arrivals and departures (National Rail via the Rail Data
Marketplace). Status before the release, licence notes and limits:
[travel-production-status.md](travel-production-status.md),
[travel-provider-approvals.md](travel-provider-approvals.md).

Every step below needs the owner's go-ahead. Nothing here creates AWS resources, IAM, or paid services.

## What changes in production

| | Before | After |
|---|---|---|
| Backend image | `lifeinuk-backend:57f25bf78ef9` | the CI image of release commit **R** (backend `main` with #58, #59 and the set-env change) |
| `/opt/lifeinuk` deploy files | digest `3dd09ff5…` | digest of R (scripts only: backup to S3 verified, `set-env.sh`; compose, nginx, db unchanged) |
| `/etc/lifeinuk/backend.env` | travel off, no provider keys | four keys, departures URL, three switches on (below) |
| Database | Flyway V15 | V15 (no migration). Refresh writes only the two latest-snapshot tables |
| Frontend | `d0b1f48` | frontend `main` **F** (homepage travel panel #27, Rail tool released) |

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
| `RAIL_ACCESS_ENABLED` | `true` (per request: 6 per viewer and 60 per instance per minute) | setting |

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

## Procedure

Not between 02:30 and 02:50 UTC (nightly backup). About 30 minutes; the API is unavailable for about a
minute in step 3; static pages are never affected.

0. **Prerequisites.** Backend and frontend PRs merged by the owner; CI green on both `main` commits (R, F);
   the backend run for R kept the `backend-image` artifact. Stage the deploy files for R:
   `deploy/ops/stage-deploy-files.sh R /tmp/deploy-files` (prints `archive_sha256`, `deploy_files_digest`).

1. **Deploy files, no restart** ([deploy-files-update.md](deploy-files-update.md)): upload the archive and
   `deploy/ops/update-deploy-files.sh` to `releases/backend/deploy-files/R/`, then one `AWS-RunShellScript`
   command with old digest `3dd09ff5df0e191501c3a0072c27170d78f14498345bbdc202120ff0fd496b78`, the new
   digest and `s3://lifeinuk-backups-919693204623-eu-west-2/postgres/`. Expect `RESULT updated …
   image=lifeinuk-backend:57f25bf78ef9 flyway=15 backup_s3_uri=s3://…/postgres/`. Then GitHub
   **Deploy production** `action` = status: the new digest, `backup_s3_uri`, `app_health=healthy`.

2. **Configuration, no restart.** Session Manager session:

   ```bash
   sudo -i
   /opt/lifeinuk/scripts/set-env.sh /etc/lifeinuk/backend.env \
     NATIONAL_HIGHWAYS_API_KEY OS_NAMES_API_KEY NATIONAL_RAIL_API_KEY NATIONAL_RAIL_DEPARTURES_API_KEY \
     NATIONAL_RAIL_DEPARTURES_BASE_URL=https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS \
     TRAVEL_REFRESH_UNDERGROUND_ENABLED=true TRAVEL_REFRESH_ROADS_ENABLED=true RAIL_ACCESS_ENABLED=true
   ```

   Paste each key at its prompt. The four fingerprints must equal the workstation's; if one differs, run
   the command again for that name. The running app is unchanged until step 3. Go straight on to step 3,
   so no restart in between starts the old image with the new settings.

3. **Backend release.** GitHub **Deploy production**: `action` = deploy, ref `main` (R),
   `approve_migrations` empty (no migration). After approval the server takes the pre-release dump,
   uploads it to S3 with an S3-verified SHA-256 (refuses otherwise, nothing changed), and recreates the app
   with image R **and** the new `backend.env` in one restart; on failure it puts 57f25bf back.

4. **Backend checks** (workstation, through CloudFront):

   ```bash
   curl -s https://lifeinuk.info/api/travel/underground | jq '.observedAt, (.lines|length)'     # within ~1 min: 200, recent, 11 lines
   curl -s 'https://lifeinuk.info/api/travel/roads?lat=51.5074&lon=-0.1278' | jq .snapshotAt    # within ~2 min: 200, recent
   curl -s 'https://lifeinuk.info/api/places/search?q=Leeds' | jq '.places|length'             # 200, places
   curl -s 'https://lifeinuk.info/api/rail/stations/KGX/disruptions?direction=arrivals' | jq .generatedAt
   curl -s 'https://lifeinuk.info/api/rail/stations/KGX/disruptions?direction=departures' | jq .generatedAt
   sleep 60; for i in $(seq 7); do curl -s -o /dev/null -w '%{http_code} ' 'https://lifeinuk.info/api/rail/stations/LDS/disruptions'; done   # 200 ×6, then 429 (per-viewer limit)
   ```

   On the server: `docker compose logs app | grep -E 'refresh (succeeded|failed|did not start)'` shows
   Underground and Roads `succeeded`; no key appears in any log line.

5. **Frontend release.** GitHub **Deploy production** (frontend) with ref F. Then
   `npm run verify:site -- --base https://lifeinuk.info --production`, and on a phone: the homepage travel
   panel in all five languages shows Underground status with its update time, Roads place search finds a
   place and lists nearby closures, Rail shows a station board in both directions.

6. **Watch.** Refresh logs every 10 minutes for the first half hour; the nightly backup at 02:40 UTC
   (`journalctl -u lifeinuk-backup`: `copied … verified by S3`).

## Rollback

Take the smallest step that fixes the problem. None needs a database restore: there is no migration, and
the refresh only replaces the latest-snapshot rows.

| Problem | Action | Effect |
|---|---|---|
| One provider misbehaves (errors, cost, provider request) | `set-env.sh /etc/lifeinuk/backend.env TRAVEL_REFRESH_ROADS_ENABLED=false` (or `…UNDERGROUND…`, `RAIL_ACCESS_ENABLED=false`) → `docker compose up -d app` | that service stops calling the provider. Its snapshot turns stale and the site stops showing it within 15 minutes; Rail answers `RAIL_DISABLED` and the tool says so |
| Wrong key | `set-env.sh … <NAME>` with the right key → `docker compose up -d app` | |
| All of the configuration | `cp -p /root/lifeinuk-env-backups/backend.env.<time> /etc/lifeinuk/backend.env` → `docker compose up -d app` | exactly the pre-release settings |
| The new image | **Deploy production** `action` = rollback (to 57f25bf; no migration, so `allow_newer_schema` stays off). 57f25bf lacks the per-viewer Rail fix: also set `RAIL_ACCESS_ENABLED=false` | |
| Frontend | **Deploy production** (frontend) with ref `d0b1f48`, or the previous run as rollback source | the site returns to the pre-#27 build |
| Deploy files | not needed: the new scripts keep the old behaviour. If ever: `update-deploy-files.sh` with the two digests swapped (deploy-files-update.md, "Undo") | |

Database restore (`restore.sh replace`) is not part of any travel rollback: it would discard everything
written since the dump and fixes nothing here (RUNBOOK.md section 3, "Application rollback and database
restore are different operations").
