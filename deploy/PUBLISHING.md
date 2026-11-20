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
