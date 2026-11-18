# Publishing content to production

Status: **design, not yet implemented** (owner decision 2026-11-17; still the
open follow-up after CI/CD, `docs/ci-cd.md`). Until `promote.py` exists, new
Guides and translation updates cannot reach production (withdrawal works, see
below); code releases never change content. The
first deployment uses the approved seed (RUNBOOK.md section 2); this document
covers every content release after that.

## Requirements

- Incremental: one Guide (or a few) per release, not the whole database.
- Preserves production-only data (travel state and evidence once refresh is
  enabled, ingestion history, production's own publication history) and the
  Flyway history.
- Repeatable, and safe to retry after any failure.
- Keeps every invariant the schema and `GuidePublicationService` enforce.
- Simple enough for one person.

## Rejected approaches

- **Replace the production schema from a local dump** for each release:
  destroys production-only data and history (owner decision: not allowed).
- **Copy rows between databases** (insert-if-absent on the Guide tables):
  the schema deliberately makes Guide data writable only through the
  publication service (session-flag guards on `guide`, `guide_source`,
  `guide_evidence*`; append-only revisions and publications; digest checks).
  A row copy would have to bypass those guards and could leave production
  in a state the application never produces.

## Design: replay the editorial workflow through production's own API

Production publishes the same inputs through exactly the code path that
local publishing uses, and the result is checked against the local result by
content hash.

```
workstation (local pipeline finished)                     production (via SSH tunnel to 127.0.0.1:8080)
  draft JSON, translation response,  ──── same inputs ───►  create batch → import → reviews → approve → publish
  review findings, checklist, date                          (GuideContentBatchService / GuidePublicationService)
  local content hashes  ◄──────────── must be equal ─────  production content hashes
```

### One-time setup (needs owner approval)

1. Production-only editorial credentials in `/etc/lifeinuk/backend.env`, all
   distinct from the local ones: `EDITORIAL_OWNER_DRAFT_PASSWORD`,
   `EDITORIAL_OWNER_APPROVAL_PASSWORD`, `LIFE_IN_UK_EDITORIAL_REVIEW_TOKEN`
   (the production review credential; the local-only review token is refused
   outside the `local` profile), `EDITORIAL_OWNER_ORIGINS=http://127.0.0.1:18080`
   and `EDITORIAL_SESSION_COOKIE_SECURE=false` (the tunnel is plain HTTP on
   loopback, encrypted by SSH). Then `docker compose up -d app`.
2. These routes stay unreachable from the internet: CloudFront's function
   and nginx both answer 404 for `/api/owner/**` and `/api/editorial/**`, and
   port 8080 is bound to the host's loopback only.

### Per release

1. Finish the release locally (published and checked on :8188), keeping the
   inputs: the zh-CN draft, the translation response, the per-locale review
   findings, checklist id and verified-on date (the section `pub/` folders
   already keep these per slug).
2. Server: `scripts/backup.sh pre-publish`.
3. Workstation: `ssh -N -L 18080:127.0.0.1:8080 ubuntu@<host>`.
4. `promote` (to be implemented, `deploy/publish/promote.py`), per slug:
   1. Read production's current publication for the slug (owner API) and
      local's published batch and locale hashes.
   2. **Skip** if production already serves the same content hashes
      (makes retries and re-runs safe).
   3. Create the batch in production from the same draft. **Stop** unless
      the package's `sourceContentHash` equals the local batch's.
   4. Import the same translation response. **Stop** unless every locale's
      `contentHash` equals the local one.
   5. Submit the reviews with the production review token and the same
      findings, approve with the approval session, and publish with
      `expectCurrentPublicationId` set to production's current publication
      (optimistic concurrency: a concurrent change makes it fail instead
      of overwriting).
   6. Nothing is public until step 5 succeeds, and publication is one
      transaction. An unfinished batch can be left to expire; the next run
      creates a new one.
5. Verify: `python3 deploy/seed/compare-api.py http://127.0.0.1:8188 http://127.0.0.1:18080
   --ignore-key sourceRevisionId --skip /sitemap.xml --skip /robots.txt` must report
   no differences. Revision IDs differ because production made its own
   revisions; content and hashes must not.
6. Frontend: `build:release` with `API_BASE=http://127.0.0.1:18080`, then
   `deploy:site --execute` and `verify:site --production`.

Withdrawing a Guide uses the backend's explicit `--guide-withdraw`
operation (with `--expect-current` and `--reason`), run in a one-off
`docker compose run --rm app …` container. The command runs at startup, and
the application then keeps running, so stop the one-off container once it has
logged the result. A frontend release follows, which removes the page.

### Properties

- Only the Guides being released change. Travel data, ingestion evidence,
  other Guides' history and `flyway_schema_history` are untouched.
- Every production write goes through the same services, guards and
  triggers as local publishing.
- It is idempotent and retryable. A failed run leaves production as it was,
  except for an unpublished batch.
- Each run is checked twice: by hash before publishing, and by the full
  public API comparison after.

### To implement next (after approval)

- `deploy/publish/promote.py`: stdlib Python, built from the `owner.py`
  calls used locally, with the base URL and credentials as parameters.
  Production credentials are read from a file outside the repository and
  never printed.
- Tests: extend `deploy/test/stack-test.sh` to give the test stack editorial
  credentials, promote one Guide from local pipeline artifacts, and check:
  - hash equality;
  - the "skip when already published" path;
  - the refusal on a stale `expectCurrentPublicationId`;
  - that travel and ingestion tables are unchanged.
