# Signed Android APKs and GitHub Releases

The release setup uses the **two secrets you have already created**, without
asking you to supply a keystore file, an alias or a third password:

| GitHub Actions secret | Purpose |
|---|---|
| `SIGNING_STORE_PASSWORD` | Unlocks the permanent signing key |
| `SIGNING_BACKUP_PASSPHRASE` | Encrypts and decrypts its stored backup |

The two secrets must stay different. Keep independent copies of both
passwords in your password manager **outside GitHub**. Do not paste
secret values into chats, issue comments, pull requests, commit messages
or workflow logs.

## Step 1 — Generate the permanent key exactly once

After the release setup PR has been approved and merged into `main`:

1. Visit **Actions → Initialize Android release signing → Run workflow**.
   Select `main`. No secrets should be typed into the form.
2. The workflow securely generates a **new PKCS12 release keystore**
   with the fixed key alias `meowmeow-release`. The key password equals
   `SIGNING_STORE_PASSWORD`, so no extra alias/password secrets are needed.
3. The keystore is encrypted with GPG (AES-256) using
   `SIGNING_BACKUP_PASSPHRASE`. Only the **encrypted** file
   `signing/release-keystore.p12.gpg` is uploaded as a backup artifact
   and proposed in a separate GitHub PR. The unencrypted key is removed
   from temporary CI runner storage. The workflow never publishes an APK.
4. Review and merge the encrypted-key PR into `main`. If repository
   settings prevent GitHub Actions from opening the PR automatically,
   open a PR from the `signing/initialize-key-...` branch created
   by the workflow. Do not rerun key creation while that branch exists.
5. **Download the encrypted backup artifact** from the bootstrap run
   and keep a secure off-GitHub copy along with your password-manager
   backups. GitHub Actions artifacts expire; Git alone is not a backup.

The encrypted key file is intentionally tracked in source control. It
contains ciphertext, not a plain key. Without the GPG passphrase,
the release signing key cannot be read. Anyone with repository write
access can change the encrypted file, so protect the `main` branch
and review signing-related changes. The bootstrap workflow refuses
to replace an existing permanent key.

**Do not delete or regenerate this keystore after the first release.**
Android requires subsequent APK updates to use the same key.

## Step 2 — Test the signed APK without publishing

Once `signing/release-keystore.p12.gpg` exists in `main`, go to
**Actions → Signed Android release → Run workflow**, select `main`
and leave `publish=false`.

This workflow decrypts the same keystore using your backup passphrase,
validates it using the store password, runs unit tests, creates the
signed release APK, verifies its Android signature, and saves
`MeowMeowFileManager-v0.3.0.apk` and `SHA256SUMS.txt` as an Actions
artifact.

Install and device-test that signed APK. Existing debug builds have
different signatures; you may need to uninstall the debug app once.
Uninstalling can remove app-local settings and folder access grants.

## Step 3 — Publish v0.3.0

Re-run **Signed Android release** on `main` with `publish=true`.
The successful run publishes `v0.3.0` to GitHub Releases with the
APK and SHA-256 checksum under Assets. Alternatively, pushing a
matching `v0.3.0` tag on main publishes the release.

Future versions must increment Android `versionCode`, change
`versionName`, and use the same encrypted signing key.

**Repository access:** The repository is currently private, so only
people who have repository access can download its release assets.
Public distribution is a separate decision.

**Safety model:** Pull-request builds have no access to signing secrets.
Only explicitly triggered main-branch workflows or release-tag runs
can access them. No plaintext keystore or signing passwords are
committed or uploaded. The ordinary debug APK workflow is unchanged.
