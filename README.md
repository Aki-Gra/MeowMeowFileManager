# MeowMeow File Manager 🐾

A small, native Android file explorer built in **Kotlin + Jetpack Compose**.

## MVP features

- Select a folder through Android's system folder picker (Storage Access Framework).
- Browse the selected folder and nested directories; return with the back button.
- Remember the selected folder across app restarts where Android retains access.
- Open files in compatible installed applications.
- Sort items by name, most recently modified, or largest first (directories stay first).
- Create folders, rename entries and permanently delete entries when the document provider allows writing. Deletion requires confirmation.
- Light and dark Material 3 UI, loading/empty/error states.

**Storage privacy:** No `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, or internet permission. The app sees only the subtree explicitly granted by the user. Some folders (including system-managed locations) cannot be selected, and not every document provider supports editing.

## Architecture

```text
app/src/main/java/com/meowmeow/filemanager/
├── MainActivity.kt                 # Activity Result folder picker + file ACTION_VIEW
├── model/FileEntry.kt              # Plain UI models and deterministic sorting
├── data/DocumentRepository.kt      # Android SAF / DocumentFile and persistable URI grants
├── ui/ExplorerViewModel.kt         # Navigation stack, async provider I/O, UI state
├── ui/ExplorerScreen.kt            # Compose file list, menus, creation/rename/delete dialogs
└── ui/theme/Theme.kt               # Light/dark app theme
```

The ViewModel contains no document-provider queries on the main thread. The repository is responsible for scoped storage access. Provider `DocumentFile`s remain in the ViewModel/repository layer; UI receives immutable snapshots.

## Open and run

1. Install a modern **Android Studio**, JDK 17, and Android SDK Platform 37.0 (Android 17).
2. Clone the repo and open its root folder in Android Studio.
3. For initial Gradle wrapper setup, run `./gradlew --version` (macOS/Linux) or `gradlew.bat --version` (Windows). On first run, the launcher downloads **Gradle's official 9.5.0 wrapper JAR**, validates its [published SHA-256 checksum](https://gradle.org/release-checksums/), and downloads the specified Gradle distribution. This initial bootstrap requires internet access and `curl`/`wget` (or PowerShell on Windows). The binary JAR is deliberately not checked into this initial commit.
4. Sync Gradle. Select the `app` configuration and Run on a device/emulator with Android 8.0 (API 26) or higher.
5. Tap **Choose a folder**; approve a directory in Android's picker. Tap folders to navigate, files to open, and ⋮ to rename/delete writable entries.

From the command line:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Limitations and next steps

- This is a **scoped** explorer, not an all-storage/root file manager; Android enforces the folder chooser's limits.
- The OS decides which apps can open each file. Built-in image/PDF/video previews, thumbnails, search, copy/move, and multi-select are not yet implemented.
- Files and folders may be read-only or have incomplete timestamps/sizes depending on provider.
- If access to a saved folder is revoked externally, select it again.
- Delete is permanent; no trash or undo yet.

## Validation

`FileEntryTest` covers folder-first sorting, size/date sorting, and filename rules. GitHub Actions is configured to run JVM tests and assemble a debug APK on pushes and PRs. Build and device validation are required before merging.

## Licensing

No license chosen yet.
