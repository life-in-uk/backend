# Incremental Guide publishing

The first Education Hub release uses a workstation client and the existing
Guide editorial APIs through a loopback SSH tunnel. The client stages only
the 55 approved Education drafts and verifies staged results after review.
The owner approves and publishes in a separate session. The backend still
enforces exact hashes, review, approval, the category checklist, optimistic
concurrency and publication activation.

The client is pinned to this release. It accepts the two approved manifest
hashes and the 11 school slugs in [promote.py](publish/promote.py). Each slug
must have exactly five locales (zh-CN, zh-TW, en, ur and hi), with category
education, DRAFT status and a null publication time. Future packages need an
independently reviewed release definition.

## Owner authorization and setup

1. Approve the release commit and CI-tested backend artifact, the exact two
   manifests and 11-slug allowlist, and the production import window. Deploy the
   approved backend first. Follow [the backend release runbook](RUNBOOK.md).
   Confirm publication is active and production contains no prior revisions or
   batches for the target slugs. Approve first-production import without a
   destination predecessor. The manifests retain external predecessor provenance.
   If a target already has prior content, stop for owner reconciliation.
2. Separately authorize the production editorial environment setup in
   `/etc/lifeinuk/backend.env`. Provision distinct, production-only
   `EDITORIAL_OWNER_DRAFT_PASSWORD`, `EDITORIAL_OWNER_APPROVAL_PASSWORD` and
   `LIFE_IN_UK_EDITORIAL_REVIEW_TOKEN`, plus `LIFE_IN_UK_EDITORIAL_APPROVAL_ACTOR`.
   Keep the approval password with the owner and the review credential with
   the independent reviewer. Set
   `EDITORIAL_OWNER_ORIGINS=http://127.0.0.1:18080` and
   `EDITORIAL_SESSION_COOKIE_SECURE=false` for the HTTP loopback tunnel.
   Apply the approved environment change by recreating the app through the
   normal Compose procedure. Keep bearer draft, bearer approval, mock review
   and local review credentials unset. Keep the `local` profile disabled.
3. Verify the app port is still bound to `127.0.0.1:8080` and both
   `/api/owner/**` and `/api/editorial/**` return 404 at the public edge.
   Verify the SSH host key and use an exclusive editorial window. Keep
   credentials out of Git, command arguments, shell history, logs and chat.

## 1. Verify the package and import DRAFTs

Use Python 3.11 or newer at the approved backend checkout on the workstation.
The check command is offline. All modes verify both manifest SHA-256 values,
each file SHA-256, each computed guide-content-v1 digest and its linked
Chinese source hash before opening a session.

~~~sh
package="$HOME/.local/state/life-in-uk/education-claude-corrections-20261119T2330Z"
umask 077
receipts=$(mktemp -d "$HOME/.local/state/life-in-uk/education-import.XXXXXXXX")
python3 -B deploy/publish/promote.py check-package "$package"
~~~

On the host as the authorized operator in `/opt/lifeinuk`, take and verify the
off-instance backup before the first editorial write.

~~~sh
BACKUP_S3_REQUIRED=1 scripts/backup.sh pre-education-import
~~~

Open the loopback tunnel in a separate terminal.

~~~sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18080:127.0.0.1:8080 ubuntu@PRODUCTION_HOST
~~~

On the workstation:

~~~sh
python3 -B deploy/publish/promote.py import-drafts "$package" > "$receipts/import.json.new" &&
  mv "$receipts/import.json.new" "$receipts/import.json"
~~~

The client prompts privately for the owner DRAFT password and keeps the cookie
and CSRF token in memory. It connects directly to loopback and rejects redirects.
It checks for public school slugs and conflicting batches before staging. It
creates Chinese sources with stable per-slug idempotency keys through
`POST /api/owner/editorial/batches`. It fills the destination translation
export with the four approved Guide objects and posts it to that batch's
translation import endpoint. The receipt contains only IDs and hashes.
The client has no review, approval or publication mode.

Compare `/sitemap.xml`, the five public locale listings and school detail
responses with the pre-import baseline. All 11 batches must remain private.

## 2. Require independent verification before approval

Give the independent reviewer the approved package and complete import receipt.
The owner retrieves fresh details using a DRAFT session and
`GET /api/owner/editorial/batches/{batchId}` over the loopback tunnel. The
reviewer independently compares the fresh details with the receipt and the
approved files, including batch IDs, Chinese source revision
IDs and hashes, and all five locale revision IDs and hashes. The reviewer uses
only the independent production review bearer credential to submit actual
review results through the tunnel.

~~~
POST /api/editorial/guide-batches/{batchId}/reviews/{locale}
Authorization: Bearer <independent production review credential>
Content-Type: application/json

{"contentHash":"<verified locale content hash>","passed":true,"findings":[...]}
~~~

After all reviews are recorded, use a fresh DRAFT session to run the second
check. It reads Guide data and requires READY batches, exact canonical content,
matching `claude-independent` reviews and the original batch and revision IDs.

~~~sh
python3 -B deploy/publish/promote.py verify-drafts "$package" \
  --receipt "$receipts/import.json" > "$receipts/verified.json.new" &&
  mv "$receipts/verified.json.new" "$receipts/verified.json"
~~~

The independent reviewer signs off on the verified receipt. The owner requires
that sign-off and both receipts before approval. A batch, revision or hash change
invalidates the sign-off and requires a fresh independent verification.

## 3. Approve and publish school batches only

Take and verify a fresh off-instance backup.

~~~sh
BACKUP_S3_REQUIRED=1 scripts/backup.sh pre-education-publish
~~~

The owner identifies each school batch in the verified receipt and checks its
current publication state. In a separate APPROVAL session, use the cookie and
CSRF token obtained via `POST /api/owner/editorial/session` with
`{"role":"APPROVAL","credential":"<owner approval password>"}`. Send the
configured Origin, session cookie and `X-CSRF-Token` with writes.

~~~
POST /api/owner/editorial/batches/{batchId}/approve
POST /api/owner/editorial/batches/{batchId}/publish
Content-Type: application/json

{"checklistId":"standard-v1","verifiedOn":"<actual ISO verification date>","expectCurrentPublicationId":null}
~~~

Null is for a verified first publication. Stop on any unexpected publication
or ID conflict. Select only the verified school batch IDs. Record each new
publication UUID as soon as it succeeds. Each publish updates one school's
five locales atomically. If interrupted, reconcile public state before
continuing with any remaining verified batch.

Confirm the API returns all 55 school locales before building the frontend.
Use the normal frontend production workflow against the published inventory.
Smoke-test the school API and canonical pages, five Education hubs, sitemap
and hreflang. Confirm all 45 known University detail API paths still return 404
and University is absent from listings, navigation and sitemap. Check public
editorial paths still return 404 and Live Services still match the baseline.
Close the tunnel. Removing or rotating temporary credentials requires
separate owner authorization.

## Recovery and rollback

- **Interrupted staging.** Before reviews begin, repeat the same import.
  The client resumes matching source-only DRAFTs and skips complete matching
  DRAFTs. It refuses conflicting batches, changed content, public slugs and
  review activity. Keep the complete receipt before review. If review or
  approval started without it, stop for owner reconciliation. A failed
  private import needs no database restore.
- **Wrong publication.** From `/opt/lifeinuk`, the owner uses the recorded
  current UUID and a reason to withdraw only the affected slug. Stop the
  one-off app once it reports withdrawal if it remains running.

  ~~~sh
  docker compose run --rm -e LIFE_IN_UK_OPERATOR="$OPERATOR" app \
    --guide-withdraw="$SCHOOL_SLUG" --expect-current="$PUBLICATION_ID" --reason="$REASON"
  ~~~

  Confirm API and sitemap removal. Rebuild against the corrected inventory or
  redeploy the prior frontend artifact for full rollback. A frontend artifact
  rollback alone leaves Guide publications in the API.
- **Code or data recovery.** Follow [the release and restore runbook](RUNBOOK.md)
  for backend image rollback or an owner-authorized whole-database restore.
  A restore loses intervening writes. Use the editorial workflow for all
  Guide writes and leave direct SQL row copying and the legacy importer unused.
