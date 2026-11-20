# Education publishing through Production CI

This release stages exactly 11 school Guides in five locales. University content
is outside its allowlist. The [pinned promotion client](publish/promote.py)
verifies both approved manifests, every file and canonical content hash, the
locale set and Chinese source links. The [host runner](publish/ci_release.py)
creates and publishes through the existing Guide APIs. Its SQL audit is read-only.

Use the backend repository's **Import private drafts** and **Publish reviewed
guides** workflows from `main`. Each needs its own approval in the existing
GitHub `production` environment. GitHub Actions uses AWS OIDC and SSM to run
the installed commands on the backend host. There is no frontend deployment,
application image change or Flyway step in either workflow.

## One-time setup, in this order

1. Keep repository variable `EDITORIAL_CI_ENABLED` unset. Protect `main`,
   including workflow, CI and IAM changes, with required owner review. Confirm
   that the existing `production` environment is main-only, requires the owner,
   and has administrator bypass disabled. Apply
   [the narrowed backend deploy role trust](../infra/aws/backend-deploy-trust.json)
   first. Verify the deployed trust explicitly requires the **Deploy production**
   workflow and its exact reusable job path. It formerly accepted any job on main in the Production environment.
   Editorial roles require both their dispatch workflow name and the exact
   reusable job path, alongside the same immutable repository subject and branch.
2. Create separate import and publish OIDC roles from
   [the Import trust](../infra/aws/editorial-import-trust.json) and
   [permissions](../infra/aws/editorial-import-policy.json), and
   [the Publish trust](../infra/aws/editorial-publish-trust.json) and
   [permissions](../infra/aws/editorial-publish-policy.json). Use the fixed SSM
   documents [LifeInUK-ImportEducationDrafts](../infra/aws/ssm-editorial-import.yml)
   and [LifeInUK-PublishEducationGuides](../infra/aws/ssm-editorial-publish.yml).
   Use an SSM Agent version that supports `interpolationType: ENV_VAR`.
   Allow neither role to update documents, start a remote shell or access S3.
   AWS requires wildcard resource scope for reading SSM command results. Keep
   all command output limited to status, hashes and audit IDs.
   Set `AWS_EDITORIAL_IMPORT_ROLE_ARN` and `AWS_EDITORIAL_PUBLISH_ROLE_ARN`.
3. Extend the existing instance role with [editorial S3 I/O](../infra/aws/instance-role-editorial-io.json).
   In the existing private bucket, require versioned packages under
   `editorial/education/packages/`. Keep packages and receipts private and
   limit the release operator's access to package uploads and receipt reads.
   The host stores content-addressed receipts under `receipts/` and
   `publications/` in the same prefix. Approve bucket versioning and any
   lifecycle changes separately. The GitHub runner never downloads draft text,
   receipt contents or credentials.
4. Install the reviewed `ci_release.py`, `promote.py` and
   `check-production-editorial.py` together in
   `/opt/lifeinuk/editorial-ci/`, owned by root and writable only by root.
   The SSM documents run these installed files under the same host lock as
   backend deployment.
5. Privately generate three distinct production-only app credentials once,
   each at least 32 visible ASCII characters. Configure
   `EDITORIAL_OWNER_DRAFT_PASSWORD`, `EDITORIAL_OWNER_APPROVAL_PASSWORD` and
   `LIFE_IN_UK_EDITORIAL_REVIEW_TOKEN` in the protected backend environment.
   Set `LIFE_IN_UK_EDITORIAL_APPROVAL_ACTOR` to the approved automation actor
   and set `EDITORIAL_OWNER_ORIGINS=http://127.0.0.1:8080`. Keep the secure
   cookie default, mock/local review and machine draft/approval tokens unset.
   Store the corresponding credentials as root-owned 0600 files named
   `draft`, `approval` and `review` in the root-only editorial directory.
   The import command reads only `draft`. The publish command reads
   `approval` and uses the host-only `review` service credential solely to
   record passing reviews for the exact approved hashes through the existing
   API. The installed backend labels this service actor `claude-independent`.
   The stored findings identify local review and GitHub owner approval as the
   source. All three service secrets stay on the host. Rotate them through the
   same private provisioning procedure if needed.
6. Apply the approved host settings once via the normal Compose app recreation.
   Confirm that the deployed backend is healthy, port 8080 is loopback-only,
   and both editorial families return
   404 at the public edge. The host client carries the secure cookie in memory
   over its direct same-host connection. The API still requires its normal
   role, Origin and CSRF defenses. Test that each editorial identity is refused
   by the other SSM document and by the original deploy role. Verify a dry
   rehearsal on a disposable database, then set `EDITORIAL_CI_ENABLED=true`.
   To disable the workflows, unset the flag and revoke the editorial roles
   if access needs immediate revocation.

## Updating an existing installation

Keep `EDITORIAL_CI_ENABLED` unset while updating the host runner and the default
version of the fixed publish SSM document. Apply the updated instance-role S3
policy to drop access to the former review archive prefix. Remove any obsolete
`allowed_signers` file specific to this runner and revoke any access granted
solely to upload signed reviews. Retain existing private audit records. Verify
the updated setup before enabling dispatch. This update uses the existing host
service credentials and needs no app recreation, image deployment or migration.

## Import and verify private drafts

Review the 55 variants locally against both approved wave manifests. Run
`python3 -B -m unittest discover -s deploy/publish` and require passing CI on
the workflow revision. Build the package ZIP from the approved manifests and
their 55 listed draft files. File entries must be exactly `waveN/MANIFEST.json` and
`waveN/drafts/<slug>/<locale>.json`, without directory entries. Upload it to
`editorial/education/packages/<archive SHA-256>.zip` with an approved private
upload identity. Record its S3 version ID.

Dispatch **Import private drafts** with the archive digest and version.
Check the digest in the run summary and approve the protected job. The host
validates archive bytes, manifests, contents, target history and public
baseline, then runs the existing backup script with `BACKUP_S3_REQUIRED=1`.
Only a successful checksum-verified off-instance backup allows writes. The
import checks all 11 batches and 55 locale revision IDs and hashes, then
compares public Guide listings and sitemap with the baseline and verifies that
all 55 detail URLs still return 404. The private receipt is
`editorial/education/receipts/<receipt SHA-256>.json`. The run logs its digest.

If import stops partway, rerun the same immutable package. Existing source-only
or complete matching DRAFT batches are reused. Any conflicting revision,
review activity or publication history stops the run for reconciliation.

## Local review and publication

Read the private import receipt and verify its SHA-256 against the successful
import run. Compare its package digest, 11 school slugs, five locales per
school, and all content hashes against the locally reviewed package. Record the
actual local review date. Keep the receipt and its batch, source and localized
revision IDs for release verification.

Dispatch **Publish reviewed guides** with the package digest and S3 version,
the import receipt SHA-256 and the local review date (`YYYY-MM-DD`). Before
approving the protected GitHub job, compare all four inputs in the run summary
with the reviewed release. That GitHub Production owner approval authorizes
publication.

The host fetches the content-addressed receipt, verifies its digest and checks
all 55 exact revisions and hashes against the package and live batches before
any write. After a verified `pre-education-publish` backup, it records the
owner-authorized review findings through the existing review API, then approves
and publishes through the existing APIs with `standard-v1` and a null expected
predecessor. Each school publishes its five locales in one database transaction.
GitHub records the owner approval. The backend records the service actor, the
import receipt digest in the review findings and the exact reviewed hashes.

For a lost response or interruption, rerun with the same package, import
receipt and review date, and give a fresh GitHub approval. The host checks every
batch before proceeding. It skips a school only if the exact imported revisions
are still public under the same checklist and date and their review records
match. Conflicting, replaced or withdrawn publications require owner
reconciliation. A successful run writes a private publication receipt with the
11 publication UUIDs and verifies all 55 public API variants, the public
listings and the sitemap. The frontend release remains separate.

## Withdrawal and recovery

Use the existing owner-authorized withdrawal command with the affected school's
recorded current publication UUID and a reason. Verify removal from the API
and sitemap before any frontend rebuild or rollback. Reconcile the same release
after a failed run. Database restore is a last resort and requires separate
authorization. See the [backend recovery runbook](RUNBOOK.md).

## Household Services & Insurance import and publication

The protected **Import private drafts** and **Publish reviewed guides** workflows
each have a `household` choice. The Household manifest pin
(`deploy/publish/household.py`) and its seven-slug allowlist are separate from
the Education manifests and slugs. Both families run through the same fixed SSM
documents (`LifeInUK-ImportEducationDrafts`, `LifeInUK-PublishEducationGuides`).
Their `release` parameter defaults to `education`, so Education dispatches are
unchanged. The GitHub roles, their trust and their SSM permissions are unchanged.

The final handoff is 35 Guide files in five locales with Sol's PASS sign-off
for every variant. Build the private ZIP from exactly `EDITORIAL-MANIFEST.json`
and `guides/<slug>/<locale>.json` (35 files), without directory entries, and
keep it private. The runner verifies the ZIP digest, its S3 version, the pinned
manifest, every file and canonical content hash, DRAFT status and the
source-to-translation linkage before opening an editorial session.

### One-time rollout (owner-approved, dispatch disabled)

With `EDITORIAL_CI_ENABLED` unset and under the host release lock:

1. Release the backend from `main` through **Deploy production**
   (`approve_migrations=none`). It carries the `household-services` sitemap
   mapping, which the runner's public sitemap check requires.
2. Install `ci_release.py`, `promote.py`, `household.py` and
   `check-production-editorial.py` together in `/opt/lifeinuk/editorial-ci/`,
   root-owned and root-writable only.
3. Replace the instance-role inline policy `LifeInUK-EditorialIO` with
   [editorial S3 I/O](../infra/aws/instance-role-editorial-io.json). It adds
   exactly `editorial/household/packages/*` (versioned reads) and
   `editorial/household/receipts/*` and `editorial/household/publications/*`
   (receipt reads and writes). Apply it before any Household publication: a run
   without the `publications/` grant publishes and then fails to store its
   receipt. Recovery is a rerun after the grant, which publishes nothing new.
4. Create new default versions of both SSM documents from
   [the import document](../infra/aws/ssm-editorial-import.yml) and
   [the publish document](../infra/aws/ssm-editorial-publish.yml).
5. Re-enable dispatch.

### Import, publish, then the frontend

Upload the versioned ZIP privately to
`editorial/household/packages/<archive SHA-256>.zip` with the release
operator's upload identity, and record the version ID. Then:

1. **Import private drafts** with `release=household`. The host requires a
   verified off-instance backup and imports 7 private DRAFT batches (35
   variants). It then checks that the public listings and sitemap are unchanged
   and that all 35 details return 404. The receipt is stored under
   `editorial/household/receipts/`.
2. **Publish reviewed guides** with `release=household`, the same package digest
   and version, the import receipt digest and the review date. The host checks
   the receipt scope and package digest and requires another verified backup.
   It records the passing reviews against the exact hashes, then approves and
   publishes the 7 batches. It verifies all 35 public variants against the
   receipt, the Household sitemap scope (35 Guides and 5 hubs) and that
   unrelated public inventory is unchanged. The receipt is stored under
   `editorial/household/publications/`.
3. Deploy the frontend from `main`. The release build prerenders the 35
   Household pages and 5 hubs from the published inventory and checks them
   against the backend sitemap. Until it ships, the Household URLs in the
   backend sitemap return 404 at the edge, so keep this step immediate.

An interrupted run is resumed by rerunning it with the same inputs. Existing
publications are kept and none is duplicated. A rerun after completion changes
nothing. An import after publication is refused. To roll back, withdraw each
Household publication with the existing owner command (`--guide-withdraw=<slug>
--expect-current=<publication UUID>`) as in **Withdrawal and recovery**. Then
redeploy the frontend so the Household pages are removed.
