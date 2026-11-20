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
   In the existing private bucket, require versioned input objects under
   `editorial/education/packages/` and `editorial/education/reviews/`.
   Keep the inputs and receipts private, and grant release operators and
   reviewer accounts only the specific upload and read access they need.
   The host stores content-addressed receipts under `receipts/` and
   `publications/` in the same prefix. Approve bucket versioning and any
   lifecycle changes separately. The GitHub runner never downloads draft text,
   review findings or credentials.
4. Install the reviewed `ci_release.py`, `promote.py` and
   `check-production-editorial.py` together in
   `/opt/lifeinuk/editorial-ci/`, owned by root and writable only by root.
   The SSM documents run these installed files under the same host lock as
   backend deployment. Register the independent reviewer's public signing key
   in root-controlled `/etc/lifeinuk/editorial-ci/allowed_signers`. Use the
   principal `claude-independent` and restrict it to the
   `lifeinuk-education-publish` SSH signature namespace.
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
   `approval` and uses `review` solely to relay the signed verdicts.
   Secrets stay on the host. Keep the reviewer signing key and owner GitHub
   approval with their respective holders. Rotate host credentials through
   the same private provisioning procedure if needed.
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

## Import and verify private drafts

Build the package ZIP from the two approved wave manifests and their 55 listed
draft files. File entries must be exactly `waveN/MANIFEST.json` and
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

## Independent evidence and publication

The independent reviewer compares the approved contents with the import receipt
and signs the exact bytes of a review object. Its fields are:

| Field | Value |
| --- | --- |
| `release` | `education-20261119T2330Z` |
| `packageSha256` | Approved package archive digest |
| `receiptSha256` | Exact import receipt digest |
| `verifiedOn` | Actual ISO verification date |
| `reviews` | Each of the 11 slugs maps to all five locales. Each locale maps to `{"passed":true,"findings":[]}` with the reviewer's actual findings. |

Sign with `ssh-keygen -Y sign -f <reviewer-key> -n lifeinuk-education-publish review.json`.
Put the unchanged `review.json` and the resulting signature in a ZIP as
`review.json` and `review.sig`. Upload to
`editorial/education/reviews/<review archive SHA-256>.zip` and record its version ID.

Dispatch **Publish reviewed guides** with the package and review digests and
versions. Compare both digests with the accepted evidence and give a new owner
approval in GitHub. The host verifies the signature and reads the live batch and
locale revisions again. It checks all 55 against the private receipt before any
editorial write. After a verified `pre-education-publish` backup, it records
the signed verdicts through the review API, then approves and publishes through
the existing APIs with `standard-v1` and a null expected predecessor. Each
school publishes its five locales in one database transaction. The GitHub run
records owner approval. The backend records the configured automation actor,
the reviewer identity and the exact reviewed hashes.

For a lost response or interruption, rerun with the same immutable inputs and
a new GitHub approval. The host checks every batch before proceeding. It skips
a school only if its exact signed revision set is still public under the same
checklist and verification date. Conflicting, replaced or withdrawn
publications require owner reconciliation. A successful run reports a private
publication receipt containing the 11 publication UUIDs and verifies all 55
public API variants and the sitemap. The frontend release remains separate.

## Withdrawal and recovery

Use the existing owner-authorized withdrawal command with the affected school's
recorded current publication UUID and a reason. Verify removal from the API
and sitemap before any frontend rebuild or rollback. Reconcile the same release
after a failed run. Database restore is a last resort and requires separate
authorization. See the [backend recovery runbook](RUNBOOK.md).
