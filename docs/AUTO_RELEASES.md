# Automatic signed Beta releases

> **Signing-identity rotation in progress (27 November 2026).** The original
> encrypted backup and checksum were removed from main. The active automatic
> release workflow still pins the OLD signing identity and uses the OLD Actions
> secrets until they are replaced. Do not trigger a new release until rotation
> is complete. See [Signing rotation and public release preparation](SIGNING_ROTATION_PUBLICATION.md).
> Keep this repository private while the old history is being assessed.

MeowMeow File Manager automatically creates a signed **Beta GitHub Release**
when application source or build configuration changes on the trusted
`main` branch. Documentation and workflow-only pushes do not publish APKs.
`workflow_dispatch` on `main` is also supported for a deliberate new run.
Pull requests never access signing secrets.

## Three required secrets

- `SIGNING_STORE_PASSWORD` — original permanent PKCS12 store password.
- `SIGNING_BACKUP_PASSPHRASE` — passphrase for the encrypted key backup.
- `SIGNING_ENCRYPTED_KEYSTORE_BASE64` — Base64 of the encrypted **File
  Manager** backup (not Pocket Folder Player's and not plaintext keystore).

The permanent File Manager signing key was created on 27 November 2026.
**Never regenerate it.** The automatic release job pins both the original
encrypted-backup SHA-256 and its public signing-certificate SHA-256.
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

Each APK retains `com.meowmeow.filemanager` and the same permanent
signing certificate, so later releases with a larger Android versionCode
can update previous release-signed installations. Existing debug-signed
builds may require a one-time uninstall.

**Private repository:** GitHub Release assets are available only to
authorized collaborators; this setup does not make APK downloads public.
A new release appearing on GitHub **does not automatically install** on
phones. No Play Store publishing or in-app update mechanism is included.

## Operational security

The former one-time key generator and old manual signed publisher are
archived as inert `.yml.disabled` files under `docs/workflow-archive/`.
They are not runnable actions. Keep at least two independently controlled,
encrypted copies of the original signing-key backup, and protect both
passwords. Existing encrypted backup files in Git history are not
plaintext private keys; avoid changing the established signing identity.

The separate debug workflow remains available for testing and PRs.
The automatic release pipeline requires Android lint to pass. It is
intentionally limited to app and build configuration file paths rather
than documentation-only changes. Many rapid pushes can replace a pending
run; only successfully executed release builds publish.
