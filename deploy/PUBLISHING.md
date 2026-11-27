# Publish reviewed Guides

Use **Publish reviewed guides** from `main` with `release=manifest` for new
Guides. One package can contain one or more new slugs within one category. Each
slug requires zh-CN, zh-TW, en, ur and hi. The backend still chooses the category's
existing editorial checklist. The owner must approve the exact package in the
protected GitHub `production` environment.

## Normal release

1. Complete editorial and language review against the existing Guide checklist.
   Copy only the reviewed DRAFT JSON files to a private local directory as
   `guides/<slug>/<locale>.json`. Preserve their bytes and compare them with the
   source handoff hashes. The Simplified Chinese file is the source revision.
   Leave `publishedAt` null. Keep this ZIP and its report private.
2. Pack and dry-run the approved scope using the reviewed backend checkout.
   Repeat `--slug` for each target. The packer checks the draft identities and
   writes `EDITORIAL-MANIFEST.json` in the ZIP. The manifest declares the
   category, review date, slugs, all locales, file SHA-256 and canonical
   `guide-content-v1` SHA-256. Save the manifest SHA-256 and the archive SHA-256
   from the dry-run report and check the listed scope.

   ```bash
   python3 -B deploy/publish/manifest.py pack <private-dir> <archive.zip> \
     --category money --verified-on YYYY-MM-DD --slug <reviewed-slug>
   python3 -B deploy/publish/manifest.py check <archive.zip> <manifest-sha256>
   ```

3. Require successful CI for the workflow revision. Use the existing release
   operator identity to upload the archive to the existing **private,
   versioned** bucket. The key is
   `editorial/household/packages/<archive-sha256>.zip`. Save the returned
   S3 object version ID with the dry-run report. The path retains the existing
   access policy and supports every manifest category.
4. Dispatch **Publish reviewed guides** with `release=manifest`,
   `package_sha256`, `package_version` and `manifest_sha256`. The old receipt
   and date inputs apply to legacy runs. Compare all three values in the GitHub
   summary to the local report and uploaded object version. **One GitHub
   Production approval authorizes both private staging and public publication
   of that exact reviewed scope.** The preview job gets no AWS permissions.
5. The fixed host command checks the S3 version, ZIP and manifest digests,
   draft files and canonical hashes before opening an editorial session. It
   checks for conflicting Guide, revision or batch rows, takes a verified
   off-instance backup, stages through the draft API, verifies all revision IDs
   and hashes, and confirms the drafts are private. It stores the immutable
   import receipt and a package-bound checkpoint, takes a second verified
   backup, then records the owner-authorized review and publishes through the
   authenticated APIs. Each Guide's five locales publish atomically.
6. Save the final private report digest printed by the workflow. Read
   `editorial/household/publications/<report-sha256>.json` for the manifest and
   package digests, backup reference, import receipt, publication UUIDs,
   source and locale revision IDs, hashes and per-locale public API catalogue
   counts. The runner verifies committed revision history and locale pointers,
   public API content and listings, and the backend's loopback sitemap and
   hreflang entries. It also compares unrelated public listing metadata and
   loopback sitemap URLs before and after publication.
7. After backend acceptance, use the separate frontend Production release.
   The frontend owns the public sitemap and release page list. Verify both
   against the new frontend release's canonical URLs, including the new Guides
   and their hreflang entries, and check the public detail and hub routes in
   each locale. The editorial workflow performs no application deployment or
   database migration.

## Recovery

- After an interruption, retry with the same archive hash, S3 version and
  manifest hash, with a fresh Production approval. The runner reuses exact
  private batches and skips already published Guides only if their open
  publication, review findings, date and hashes match the immutable import
  receipt and approved release. If publication committed before a verification
  or report write failed, the retry verifies the committed release before
  saving its report. The report's backup reference is from the verification
  attempt that produced the report. A missing checkpoint after review,
  foreign revision, mismatched package or withdrawn publication stops the
  run for owner reconciliation.
- For an authorized withdrawal, use the existing backend owner command
  `--guide-withdraw=<slug> --expect-current=<publication UUID> --reason=<reason>`.
  Use the exact current UUID printed for each completed publication or recorded
  in the private report. Verify the Guide and locale APIs return 404 and
  the sitemap excludes the withdrawn URLs. Run the ordinary frontend release
  to remove its static pages. Withdrawal preserves audit history and prevents
  replay of the original manifest. This workflow handles new slugs; replacement
  revisions use the existing owner revision and publication process.
- If a batch stops partway, each already published Guide remains public.
  Resume the exact release or withdraw those publications individually after
  owner approval. The verified backups are available for separately authorized
  database recovery as a last resort. See the [backend runbook](RUNBOOK.md).

## One-time rollout on the existing installation

1. Keep editorial dispatch disabled while installing. Require owner review of
   the code and confirm that GitHub `production` is main-only, requires owner
   review and disables administrator bypass.
2. Install `ci_release.py`, `manifest.py`, `promote.py`, `household.py` and
   `check-production-editorial.py` together from the approved commit under
   `/opt/lifeinuk/editorial-ci/`, root-owned and root-writable only. Update
   the default version of the existing
   [LifeInUK-PublishEducationGuides SSM document](../infra/aws/ssm-editorial-publish.yml).
   The workflow name and reusable publish job path remain the same, preserving
   the existing OIDC trust. Keep the existing roles, S3 prefixes, credentials,
   backup script and release lock.
3. Verify the existing checker passes, the backend is healthy and loopback-only,
   both editorial route families are publicly inaccessible, and the legacy
   import role cannot invoke the publish document. Verify the exact installed
   script hashes and document parameters, then re-enable dispatch. The runner
   uses separate existing draft, review and approval credentials on the host.
   The GitHub runner sends only digests and the object version to SSM.
4. If rollout needs to be reverted, disable dispatch, restore the previous
   host scripts and SSM default document version, and verify the legacy paths.
   Restoring the runner does not withdraw any Guide already published.

### Legacy compatibility

Education and Household retain their original pinned manifests, separate
import and publish approvals, S3 object keys, and receipt schemas. Use their
original workflow choices only to finish or verify those releases. The two
legacy validators preserve their original boundaries, including Education's
school-only scope. New releases in those categories use the manifest option.
For the original operations and provisioning details, see the
[legacy release runbook at the previous revision](https://github.com/life-in-uk/backend/blob/617cd1a621d5da4afc2e79b7cce0c7ca3f5a6646/deploy/PUBLISHING.md).
