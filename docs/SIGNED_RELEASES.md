# Signed Android APKs and GitHub Releases

The signed release workflow builds APKs that future releases can update using
the same permanent Android signing key. The ordinary debug build remains separate.

## GitHub Actions secret names

Add these four repository secrets in GitHub Settings > Secrets and variables >
Actions > Repository secrets. The names must match exactly:

| Name | Meaning |
|---|---|
| ANDROID_KEYSTORE_BASE64 | Base64-encoded contents of the permanent .jks or .keystore file |
| ANDROID_KEYSTORE_PASSWORD | Password for the keystore |
| ANDROID_KEY_ALIAS | Alias of the Android signing key |
| ANDROID_KEY_PASSWORD | Password of the alias (can equal keystore password) |

Keep the original keystore and its passwords in a separate secure backup.
Do not paste any secret values into chats, issues, pull requests, code or
GitHub Actions logs. Never commit the keystore to the repository.

The workflow reconstructs the keystore only on a temporary CI runner with
restricted file permissions, uses environment variables for passwords,
runs unit tests, assembles the release APK, verifies its signature with
Android apksigner, and removes the temporary keystore. The signed APK and
SHA256SUMS.txt are provided as a downloadable Actions artifact.

## First release: v0.3.0

After the release setup PR is merged into main:

1. Open GitHub > Actions > Signed Android release > Run workflow.
2. Choose the main branch and keep Publish unchecked (false).
3. Check the run succeeds and download signed-meowmeow-v0.3.0 from Artifacts.
4. Test the installed APK. An existing debug APK signed by another key
   may require uninstalling first; uninstalling can remove app-local settings.
5. When happy with the signed APK, run the same workflow again on main,
   this time setting Publish to true.
6. The workflow creates the GitHub Release v0.3.0 and attaches
   MeowMeowFileManager-v0.3.0.apk and SHA256SUMS.txt under Assets.

Alternatively, pushing a v0.3.0 Git tag referencing a commit on main triggers
a release build and publication. The tag name must match Android versionName.
The workflow rejects publishing a tag that already has a release.

Future versions must increase Android versionCode (monotonically) and update
versionName before publishing. Back up the signing key: losing it prevents
updating installations signed with that key.

The repository is currently PRIVATE. Anyone downloading the release from
GitHub must have access to this repository. Public distribution would require
a separate, approved change to repo visibility or a public download host.

The normal Android build PR workflow never uses these signing secrets.
