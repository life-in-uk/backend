# Travel evidence retention (V11)

Status: **migration prepared; NOT applied to any existing database.** It was exercised only in
the test suite's temporary, isolated PostgreSQL clusters.

## Policy (owner decision, 2026-11-11)

| Data | Retention | Basis |
| --- | --- | --- |
| TfL Underground raw snapshots (SUCCESS runs + artifacts) | **72 hours** from EvidenceArtifact observation time (the run must also have completed 72 h ago) | the recorded TfL qualification's "approximately 72 hours" target |
| National Highways raw snapshots (SUCCESS runs + artifacts) | **7 days** after completion | **PROPOSED**: pending explicit policy and licensing approval; the recorded NH qualification sets no retention |
| Failed runs (no artifacts) | 30 days after completion | owner decision |
| Runs left `STARTED` by an interrupted process | 30 days after start | owner decision |
| Run/artifact behind the **current** Underground or Roads snapshot | kept while current | checked on every row |
| Current projected state (`underground_current_*`, `roads_current_snapshot`) | never touched | separate lifecycle (V4/V7) |
| GOV.UK Bank Holidays evidence | never eligible | its API serves the artifact |
| Guide content, evidence, revisions, publication | out of scope | no reference to ingestion evidence |

Changing a period requires a new reviewed migration.

## Affected tables and dependencies

- `ingestion_run` → `source_endpoint` (FK), with the row trigger `protect_ingestion_run`.
- `evidence_artifact` → `ingestion_run` (FK, no cascade), with the row trigger
  `protect_evidence_artifact`.
- `underground_current_snapshot.evidence_artifact_id` and `roads_current_snapshot.ingestion_run_id`
  are identifiers **without** foreign keys, so V11 protects them explicitly.
- No Guide table references ingestion evidence.
- `/api/bank-holidays` reads its artifact directly.
- Replay commands can no longer replay an expired artifact or run. That is expected.

## What V11 does (non-destructive)

1. Adds the index `ingestion_run (source_endpoint_id, started_at)`.
2. Adds `travel_evidence_run_expired(run_id)`, the single eligibility rule.
3. Adds the read-only view `travel_evidence_expiry_candidates`.
4. Replaces the two trigger functions with identical INSERT and UPDATE rules and messages.
   DELETE is allowed only when **all three** hold:
   - the transaction-local flag `life_in_uk.travel_evidence_expiry = on` is set;
   - the current role holds `EXECUTE` on `expire_travel_evidence`
     (`travel_evidence_expiry_authorised()`);
   - the row is eligible.

   A run with remaining artifacts can never be deleted.
5. Adds `expire_travel_evidence(batch_size = 500, dry_run = true)`. It takes the oldest eligible
   runs first, skips rows locked by another writer, and returns runs, artifacts and bytes.
   `EXECUTE` is revoked from `PUBLIC`.

**Privileges.**
- Destructive cleanup needs an explicitly authorised privilege: `EXECUTE` on
  `expire_travel_evidence`.
- The schema owner holds it. `PUBLIC` is revoked, and any other role, such as the runtime role
  configured by the Guide publication migration, must be granted it deliberately by an
  administrator.
- Table DML alone, including `DELETE`, is not enough. The triggers refuse such deletes even if the
  flag is set.

**Defaults.** Nothing runs automatically. `dry_run` defaults to `true`, and no schedule exists on
this branch.

The scheduled job that calls the function (`TravelEvidenceExpiry`: off by default, a dry run by
default, daily, batched, under advisory lock key 3) depends on PR #52's scheduler. It follows as a
separate change after #52 and this migration are merged. Until then, expiry is a manual,
reviewed operation.

## Migration numbering

- `main` currently ends at V10. This migration is **V11**.
- The unmerged Guide publication migrations were renumbered on their own branch
  (`feature/guide-publication-completion`, commit `8f99165`):
  - V11 `create_guide_publication` → V12
  - V12 `guard_activated_guide_projection` → V13
  - V13 `guide_runtime_permissions` → V14
- File contents are unchanged (same checksums).
- Originals are kept in `backup/guide-publication-completion-pre-renumber-2026-11-11`, in
  `backup/50-guide-publication-withdrawal-pre-renumber-2026-11-11`, and in
  `~/projects/life-in-uk-backend-backups/2026-11-11-migration-renumber/`, which holds the files,
  a git bundle and checksums.
- Either change can merge first. A database with only V1–V11 later receives V12–V14 in order.
  A database that took the publication branch first (V1–V10, V12–V14) would see V11 as
  out-of-order, so **merge V11 before deploying the publication work anywhere**.

## Existing local development database (separate approval)

It records `11 | create guide publication` (the old numbering). With either new branch, Flyway
validation fails, because version 11's description differs from the resolved V11. Nothing has been
changed.

The proposed one-time remediation, which is **not executed**:

1. Stop every backend using `life_in_uk` (currently the IDE backend on 8080).
2. Take a full backup: `pg_dump -Fc -d life_in_uk -f life_in_uk-pre-renumber.dump`. Verify it
   with `pg_restore -l`, and restore it once into a scratch database.
3. As the schema owner, in one transaction, move the recorded publication row to its new version.
   Content is unchanged, so the checksum still matches:
   `UPDATE flyway_schema_history SET version='12', script='V12__create_guide_publication.sql'
    WHERE version='11' AND description='create guide publication' AND success;`
   Check that exactly one row changed.
4. Apply the new V11 once, out of order, for this database only: start the backend once with
   `--spring.flyway.out-of-order=true`. V11 is non-destructive. Then remove the flag.
5. Validate: the application starts with default Flyway settings, and `flyway_schema_history`
   shows 1–12 succeeded.
6. Retire or renumber the old `feature/50-guide-publication-withdrawal` checkout. It still carries
   the old V11 and would fail validation against the remediated database.

Alternative: recreate the development database from the new sequence and restore its data from
the dump. That is heavier, but avoids editing history.

## Expected storage

Measured on 2026-11-11 during 36 live runs:

- TfL: 12.2 KB per run raw, about 1.5 KB stored.
- National Highways: about 198 KB per run raw, about 29 KB stored.

| Default intervals | Window | Runs kept | Raw | On disk (TOAST-compressed) |
| --- | --- | --- | --- | --- |
| TfL (720/day) | 72 h | 2,160 | ≈ 26 MB | ≈ 3 MB, plus about 1 MB overhead |
| National Highways (144/day) | 7 days | 1,008 | ≈ 200 MB | ≈ 30 MB |
| Failed runs | 30 days | small | 0 | < 1 MB |
| **Total steady state** | | | ≈ 230 MB | **≈ 35 MB** |

Without expiry, storage grows by about 5 MB per day on disk. Freed space is reused by autovacuum.

## Backup and recovery (before the first real deletion)

1. **Applying V11** is non-destructive. Take the normal pre-migration `pg_dump -Fc` and test a
   restore.
2. **Dry run:** `SELECT * FROM travel_evidence_expiry_candidates;` and
   `SELECT * FROM expire_travel_evidence(500, true);`. Confirm that no Bank Holidays or
   current-state run is listed.
3. **First deletion:** run `pg_dump -Fc -t ingestion_run -t evidence_artifact` and keep it for the
   agreed recovery window. A backup is itself retained data under the licence review. Then run one
   manual batch in a transaction.
4. **Recovery:** restore the dump into a scratch database, then copy the needed rows back as the
   owner (runs first, then artifacts; the INSERT rules still apply). Current state is never
   affected.
5. **Rollback of V11:** a forward migration restoring the V2 trigger bodies and dropping the
   function, view and index. Flyway Community has no undo.

## Tests (`TravelEvidenceExpiryMigrationTest`, isolated PostgreSQL)

- **Boundaries:**
  - TfL at 73 h is expired; at 71 h it is kept.
  - National Highways at 8 days is expired; at 6 days it is kept.
  - FAILED at 31 days is expired; at 29 days it is kept.
  - STARTED at 31 days is expired; at 8 days it is kept.
  - Bank Holidays at 400 days is kept.
  - The dry run equals the applied result and deletes nothing, and the view agrees.
- **Current state remains available:** after expiry, the 40-day-old runs behind the current
  snapshots and their artifact are kept, and `/api/travel/underground` and `/api/travel/roads`
  still return 200.
- **Privileges:** a non-owner role with full table DML (as the runtime role receives) cannot
  delete eligible evidence, even with the flag set, and cannot call the function. It can expire
  eligible evidence only after an explicit `GRANT EXECUTE`. `PUBLIC` cannot execute it.
- **Plain deletes and updates are still rejected,** with the original messages. The hand-set flag
  cannot delete recent or Bank Holidays evidence, or a run with artifacts. The flag is
  transaction-local.
- **Batching:** runs go oldest first, and an invalid `batch_size` is rejected.
- **The full sequence** V1–V11 plus the renumbered V12–V14 is verified on a fresh cluster (see
  the PR or report).
