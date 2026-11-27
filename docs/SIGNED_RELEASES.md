# Signed APK and GitHub Releases

MeowMeow File Manager uses a dedicated permanent signing identity.
The manual-only workflows follow Pocket Folder Player's verified key generation,
restore-test, unsigned APK build, zipalign, apksigner and verification process.

## Required GitHub Actions secrets

| Secret | Purpose |
|---|---|
| `SIGNING_STORE_PASSWORD` | Unlocks the permanent PKCS12 signing key |
| `SIGNING_BACKUP_PASSPHRASE` | Encrypts/decrypts its backup |

Keep these two passwords different and preserve copies securely outside GitHub.
Never paste secret values into chat, source code, pull requests or build logs.

## 1. Initialize the permanent signing key once

On GitHub, open **Actions → Initialize Android release signing → Run workflow**.
Select **main**, type `GENERATE_NEW_SIGNING_KEY` and run it. No PR is needed.

The workflow checks both secrets (minimum lengths 16 and 20 characters),
generates a unique RSA-3072 PKCS12 keystore with alias `meowmeow-release`,
encrypts it using GPG AES-256 and the backup passphrase, then **decrypts a
test copy, compares the original/restored bytes and verifies the key alias**.

Only the encrypted file `signing/release-keystore.p12.gpg` and its checksum
`signing/release-keystore.sha256` are committed directly to `main` by the
workflow. No plaintext signing key or passwords are committed. The workflow
also uploads a seven-day encrypted backup artifact. Download the encrypted
backup immediately and keep secure copies outside GitHub.

**Never repeat key generation once an identity exists.** A new key would
prevent installing updates over apps signed with the original identity.
If the workflow cannot push its encrypted file directly to `main` because
of branch protection or a concurrent commit, preserve the encrypted
backup artifact and do NOT regenerate the signing identity. The encrypted
key will have to be installed using an authorized path before signing.

## 2. Build a signed test APK

On GitHub, open **Actions → Signed Android release → Run workflow**.
Select **main** and leave `publish=false`. The workflow will:

- Validate the committed encrypted backup's SHA-256 checksum.
- Run JVM unit tests and build an **unsigned** Android release APK.
- Restore and validate the same permanent signing keystore.
- Align the unsigned APK using `zipalign`, then sign using `apksigner`.
- Verify the signature and alignment and calculate a SHA-256 checksum.
- Upload the directly downloadable `MeowMeowFileManager-Signed.apk`
  Actions artifact (retained for 30 days).

Test the APK on your device. The existing debug-signed APK may have
a different certificate; you may need to uninstall it first (potentially
losing local app settings and folder access grants). Future releases
with the same key and a higher `versionCode` can update normally.

## 3. Publish v0.3.0

Run **Signed Android release** again on `main` with `publish=true`.
The successful run creates GitHub Release `v0.3.0` and attaches
`MeowMeowFileManager-v0.3.0.apk` and `SHA256SUMS.txt`.

There are **no automatic tag, push or pull-request signing triggers**.
Only a manual workflow on trusted `main` accesses signing secrets.
Future versions must increment Android `versionCode` and update
`versionName`, keeping the same permanent signing key.

MeowMeow's repository is currently private. Its GitHub Release
downloads therefore require repository access.

## CI quality notes

Standard CI runs unit tests and compiles debug and unsigned release
APKs. An expanded CI run also attempted Android lint, but found
existing reader-code lint errors unrelated to signing. Those are
separate follow-up fixes; do not claim lint has passed.

## Recovery

The encrypted backup, its GPG passphrase and the keystore password
must all be retained for disaster recovery. The backup artifact
expires after seven days, and the private Git repository is not
an adequate substitute for your own independently retained backup.
