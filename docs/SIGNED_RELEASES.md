# Signed APKs and GitHub Releases

MeowMeow File Manager uses a dedicated signing key, separate from Pocket
Folder Player. The signing process follows Pocket Folder Player's verified
one-time generation, encrypted-backup restore check, unsigned release build,
zipalign and apksigner signing, and signature verification workflow.

## Required GitHub Actions secrets

Only these **two existing repository secrets** are needed:

| GitHub Actions secret | Purpose |
|---|---|
| `SIGNING_STORE_PASSWORD` | Protects the permanent PKCS12 keystore |
| `SIGNING_BACKUP_PASSPHRASE` | Encrypts/decrypts its GPG backup |

They must be different, with at least 16 and 20 characters respectively.
Keep your own copies in a password manager outside GitHub. Never paste
secret values into chat, pull requests, code, logs, or issue comments.

## Step 1 — Generate and restore-test the permanent key exactly once

After PR #8 is reviewed and merged to `main`:

1. On GitHub, open **Actions → Initialize Android release signing**.
2. Select **main** and enter exactly `GENERATE_NEW_SIGNING_KEY` in the
   confirmation field. The workflow cannot run on a PR or non-main branch.
3. It generates one new RSA-3072 PKCS12 key using alias
   `meowmeow-release`, and encrypts the keystore with AES-256 and
   `SIGNING_BACKUP_PASSPHRASE`.
4. Before saving anything, it decrypts a test copy, compares the
   original and restored bytes, and verifies the key alias/password.
5. It uploads **only the encrypted backup** as a GitHub Actions
   artifact (retained for **7 days**) and creates a separate branch/PR
   containing `signing/release-keystore.p12.gpg` and
   `signing/release-keystore.sha256`. If GitHub blocks bot-created
   PRs, open the PR for the indicated `signing/initialize-key-...`
   branch manually.
6. Save the encrypted backup securely **outside GitHub**, together with
   your separate password-manager backups. Then review and merge the
   encrypted key PR into `main`.

The encrypted backup is retained in the private repository for future
builds, but **never the plaintext keystore**. The checksum detects
accidental corruption or unreviewed changes between signing runs.
Do not regenerate or replace the key after generating it. Losing it
prevents updating existing installed APKs under the same app identity.
The workflow refuses to proceed if the key or an earlier initialization
branch already exists.

## Step 2 — Build a signed APK without publishing

Go to **Actions → Signed Android release → Run workflow**, select
**main** and leave `publish=false`.

The workflow:
- Runs unit tests and Android lint and builds an **unsigned** release
  APK. No signing secret is passed into Gradle or pull-request builds.
- Checks the SHA-256 of the tracked encrypted key backup.
- Decrypts the backup on the temporary runner, validates its alias
  and password, and signs the aligned APK with Android `apksigner`.
- Verifies the final APK signature and alignment, writes a checksum,
  and removes temporary plaintext signing material.
- Uploads a directly downloadable artifact containing
  `MeowMeowFileManager-v0.3.0.apk`, retained for 30 days.

Test the signed APK on your device. You may need to uninstall previous
**debug-signed** builds once due to different Android certificates,
which can erase app-local settings. Future release APKs must use the
**same key** and increment their `versionCode` to update reliably.

## Step 3 — Publish GitHub Release

When the signed artifact has been tested, run **Signed Android release**
on `main` again with `publish=true`. The workflow publishes
`v0.3.0` with the signed APK and `SHA256SUMS.txt` as Release assets.

There are **no automatic tag/push/PR signing triggers**. Every signing
operation requires a manual run of the trusted workflow on `main`.
Subsequent versions require a strictly increasing Android
`versionCode`, a new `versionName`, and the same signing key.

**Repository privacy:** MeowMeow's repository is currently private.
Only users with repository access can download GitHub Release assets.
Public distribution requires a separate approved change.

**Recovery:** The encrypted backup plus the backup passphrase and store
password are all required to restore the signing key. Keep independent
copies of the encrypted file and both secrets. GitHub's temporary
artifact is not a durable backup.
