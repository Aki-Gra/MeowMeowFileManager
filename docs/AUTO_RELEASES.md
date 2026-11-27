# Automatic signed Beta releases

> **Signing identity rotated before public launch (27 November 2026).**
> A new keystore was generated and its encrypted backup restore-tested in
> [GitHub Actions run 46013967169](https://github.com/Aki-Gra/MeowMeowFileManager/actions/runs/46013967169).
> The release workflow now pins the replacement certificate and backup hashes.
> The replacement signing identity has been verified by successful signed Beta publication.
> The repository is now public. Historical encrypted backups may remain in Git history;
> see the [rotation/publication guide](SIGNING_ROTATION_PUBLICATION.md) for historical context.

MeowMeow File Manager automatically creates a signed **Beta GitHub Release**
when application source or build configuration changes on the trusted
`main` branch. Documentation and workflow-only pushes do not publish APKs.
`workflow_dispatch` on `main` is also supported for a deliberate new run.
Pull requests never access signing secrets.

## Three required secrets

- `SIGNING_STORE_PASSWORD` — current permanent PKCS12 store password.
- `SIGNING_BACKUP_PASSPHRASE` — passphrase for the encrypted key backup.
- `SIGNING_ENCRYPTED_KEYSTORE_BASE64` — Base64 of the encrypted **File
  Manager** backup (not Pocket Folder Player's and not plaintext keystore).

The replacement File Manager signing key was generated on 27 November 2026.
**Do not regenerate it again.** The automatic release job pins the NEW
encrypted-backup SHA-256 and public signing-certificate SHA-256.
A mismatch stops signing and publishing.

## Two-job isolated pipeline

1. A least-privilege `build` job checks out the triggering `main`
   commit, runs JVM unit tests and Android lint, and produces an unsigned APK.
   It has **no access to signing secrets**.
2. It uploads the unsigned APK as a standard **ZIP artifact** for job-to-job
   transfer (not a raw artifact).
3. The isolated `sign_and_publish` job does **not check out app source or
   execute Gradle**. Before secrets are used, it rejects existing tags and
   outdated workflow runs to avoid publishing APK downgrades.
4. It decrypts the exact permanent signing key from the three GitHub secrets,
   verifies its encrypted-file SHA-256, validates the keystore alias, runs
   `zipalign` then `apksigner`, and checks signature schemes v2/v3,
   certificate SHA-256, `applicationId`, Android versionCode and versionName.
5. Only after every check passes, it uploads a signed APK and `.sha256`
   checksum as assets of a GitHub **prerelease**. If any stage fails,
   nothing is published.

## Versioning and updates

Previously verified signed v0.3.0 test APK used `versionCode=3`.
The first automatic release job uses `versionCode=3001`, versionName
`0.3.1`, and Git tag `v0.3.1-beta.1`; the next successful new run
increments them using the workflow run counter, leaving safe gaps after
failed or skipped runs. Different workflows have independent counters.
Do not reset/recreate this release workflow without reviewing the
version-code base and existing tags. Never rerun an old run to publish
an update; use a fresh run. A full major/minor version switch requires
updating this workflow's prefix and version/tag guards deliberately.

Each subsequent APK must retain `com.meowmeow.filemanager` and the newly
rotated signing certificate. New releases with larger Android versionCodes
can update APKs signed with this new key. Older APKs signed before the
pre-public key rotation generally require uninstalling first. Existing debug-signed
builds may require a one-time uninstall.

**Public repository:** Signed APKs and checksums are available in GitHub Release assets.
A new release appearing on GitHub **does not automatically install** on
phones. No Play Store publishing or in-app update mechanism is included.

## Operational security

The former one-time key generator and old manual signed publisher are
archived as inert `.yml.disabled` files under `docs/workflow-archive/`.
The pre-public key rotation workflow is manual-only and produces an
**encrypted artifact**, not a tracked signing file.
The archived workflows are not runnable actions. Keep secure independent encrypted backups of the new signing identity and
protect both passwords. Old encrypted backups may remain in historical Git commits; Actions history cleanup does not rewrite Git history. Never reuse the superseded key.

The separate debug workflow remains available for testing and PRs.
The automatic release pipeline requires Android lint to pass. It is
intentionally limited to app and build configuration file paths rather
than documentation-only changes. Many rapid pushes can replace a pending
run; only successfully executed release builds publish.
