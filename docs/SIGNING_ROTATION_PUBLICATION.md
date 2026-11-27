# Rotating the release key before making this repository public

Status (2026-11-27): The old encrypted release keystore and checksum were
deleted from the current **main** tree. The old ciphertext remains in historical
commits and other refs. The replacement key has **not** yet been generated,
and GitHub Actions still holds the old signing secrets. **Keep this repository
private until the old history is cleaned.**

There are currently no external users requiring update compatibility, so this
one-time signing-key reset is intentional. APKs signed with the replacement key
will generally not install as upgrades over the previous signed Beta.

## No-code option: generate the new signing key entirely on GitHub

**Recommended for anyone without Python or a development computer.**
The repository must remain PRIVATE during this process.

1. Open **GitHub → MeowMeowFileManager → Actions**.
2. Select **Rotate Android signing key (manual, private repository)**.
3. Choose **Run workflow** on branch **main**, type **ROTATE_SIGNING_KEY**
   in the confirmation box, and run.
4. When the job succeeds, download the **New-FileManager-Encrypted-Signing-Key**
   artifact and open the ZIP using your phone or computer's ordinary file manager.
5. Inside the ZIP, find
   **COPY-TO-SIGNING_ENCRYPTED_KEYSTORE_BASE64.txt**. Copy its entire line.
   Treat this as sensitive backup data; do not paste it in chat.
6. Open **Settings → Secrets and variables → Actions** in the GitHub repo.
   Edit **SIGNING_ENCRYPTED_KEYSTORE_BASE64** and replace its old value with
   the copied line. Keep the two existing password secrets unchanged.
7. Securely retain **release-keystore.p12.gpg** from the artifact as an offline
   encrypted backup (GitHub expires the artifact after seven days).
8. The **PUBLIC-VERIFICATION-HASHES.txt** file contains the two non-secret
   hashes needed to update the auto-release workflow. Do not put the
   encrypted key itself or either password into the workflow source.
9. Update the public SHA-256 pins in the auto-release workflow. Then trigger
   a fresh Beta build on main and confirm signing, tests, lint, version
   metadata, and release publishing all succeed.

The workflow cannot update GitHub repository secrets itself; the one-time
copy/paste in GitHub Settings is required. Generation does not commit
plaintext or encrypted key material to the repository. This replaces
the signing identity but **does not remove the old ciphertext from Git
history**: history still must be cleaned or a fresh public repository
created before making the original repository public.

## Alternative: generate locally (requires Python), outside GitHub

On a trusted computer with Python 3.9+, JDK keytool and GnuPG installed:

    python3 scripts/rotate-release-signing.py

The helper prompts privately for two DIFFERENT strong passwords and
generates RSA-3072 PKCS12 alias `meowmeow-release` and an AES-256 GPG-encrypted
backup. It tests decryption and keystore integrity, then writes its output
to `~/MeowMeowFileManager-signing-new/` outside the repository.

Treat the output as **private**. Keep secure backup copies somewhere other than
the source repository. Never commit the encrypted backup or paste its base64,
passwords or plaintext key into source code, chat, issues, PRs or logs.

## 2. Replace exactly three Actions secrets

In **Settings → Secrets and variables → Actions**, replace:

- `SIGNING_STORE_PASSWORD` with the new keystore password.
- `SIGNING_BACKUP_PASSPHRASE` with the new GPG backup passphrase.
- `SIGNING_ENCRYPTED_KEYSTORE_BASE64` with the **entire one-line** contents of the local
  `encrypted-keystore-base64.txt` file (not plaintext PKCS12).

Do not delete the new encrypted backup from your secure offline storage.
GitHub repository secrets cannot be retrieved later.

## 3. Update the two public verification pins

Open `.github/workflows/auto-release.yml` on a development branch.
In the `sign_and_publish` job's `env` section, replace:

- `SIGNER_CERT_SHA256` — copied from local `verification.txt`
- `ENCRYPTED_BACKUP_SHA256` — copied from the same file

These are public fingerprints, not passwords. Do not delete the checks,
replace them with empty values, or bypass signature verification.

Merge the workflow change after the three secrets are updated. Then run
**Actions → Auto-publish signed MeowMeow File Manager Beta → Run workflow**
on main. Verify build, tests, lint, signing and publication succeed.
The release must have a NEW signing certificate, correct package identity
and increasing versionCode. Test installation from a clean device state.

## 4. Clean the old repository history BEFORE visibility changes

Deleting tracked files is insufficient. The old encrypted backup may still be
visible in historical commits, old branches, tags, pull-request references and
caches. The required paths to remove from ALL history are:

    signing/release-keystore.p12.gpg
    signing/release-keystore.sha256

Use a fresh clone and `git-filter-repo` or a clean new public
repository with fresh Git history. Rewriting existing history requires a
verified backup, coordinated force-push of rewritten refs, and consideration
of GitHub-retained pull-request references. Do not make this repository public
until all accessible references have been audited. Contact GitHub Support if
old sensitive data remains available via retained references.

## 5. Branch protection

Once public, configure a GitHub repository ruleset on main matching
Pocket Folder Player: require PRs, zero approvals for solo work, require
a uniquely named Android CI check, require up-to-date checks, disallow
deletions and non-fast-forward pushes, and permit no bypass.
