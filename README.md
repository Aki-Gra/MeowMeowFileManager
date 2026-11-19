# MeowMeow File Manager 🐾

A native Android file explorer built in Kotlin and Jetpack Compose.

## Screens

- **Files:** Browse shared storage after explicit All files access approval, or use Android's folder picker as a scoped fallback. Browse Downloads directly, open files with other apps, sort, create folders, rename and delete after confirmation.
- **Storage overview:** Shows used/available space for primary shared storage, a shortcut to Android's system storage settings, a Downloads shortcut and a link to Cleanup.
- **Cleanup:** Scans Downloads for files **50 MiB or larger**, shows candidates with their sizes, and lets users select and confirm permanent deletion. Nothing is automatically deleted.

## Permissions and privacy

On **Android 11+**, full file management uses Android's special **All files access** setting. MeowMeow shows a user-initiated route to the system permission screen.

- **Choose a folder** via the Storage Access Framework works without All files access. Existing SAF browsing remains intact for users who decline permission.
- Android still protects directories such as Android/data and Android/obb; root access is not granted.
- The direct explorer currently covers **primary shared storage only**, not every SD card, USB drive or protected system directory.
- The Cleanup tab **only scans Downloads**, never removes directories, and revalidates file size and modification time before a requested deletion.
- Deletion is permanent. There is no recycle bin or automatic cleanup.
- No network permission, cloud upload, analytics or background scanning.
- Publishing to Google Play with MANAGE_EXTERNAL_STORAGE requires file-manager justification and a Play Console declaration. See [Android storage documentation](https://developer.android.com/training/data-storage/manage-all-files) and [Play policy](https://support.google.com/googleplay/android-developer/answer/10467955).

## Architecture

- **MainActivity.kt:** top-level tabs, permission settings and FileProvider-based opening.
- **model:** FileEntry, CleanupCandidate and sorting/threshold helpers.
- **data/DocumentRepository.kt:** persisted Storage Access Framework tree access.
- **data/LocalFileRepository.kt:** access-checked local shared-storage operations.
- **ui/ExplorerViewModel.kt:** scoped explorer state.
- **ui/DeviceExplorerViewModel.kt:** local explorer state.
- **ui/CleanupViewModel.kt:** review, selection, and deletion state.
- **ui/ExplorerScreen.kt / StorageScreens.kt:** Material 3 screens and dialogs.

Filesystem and provider work happens on Dispatchers.IO rather than the main thread.

## Build and run

1. Install modern Android Studio, JDK 17 and Android SDK Platform 37.
2. Check out `feat/android-file-explorer` (the single consolidated MVP branch).
3. Run the command: ./gradlew :app:testDebugUnitTest :app:assembleDebug
4. Install the debug APK from app/build/outputs/apk/debug/app-debug.apk.
5. On Android 11+, use **Enable full file access** in Files or Storage to open Android settings, allow access, then return to MeowMeow. Explore device storage or go straight to Downloads.
6. Open Storage for the usage summary, then Cleanup to scan, select and optionally delete large Downloads files.

The bundled lightweight Gradle launcher bootstraps a checksum-verified Gradle wrapper JAR on first use. GitHub Actions runs unit tests and assembles a debug APK for pull requests; check its result before merging.

## MVP limitations

- Cleanup is a large-downloads reviewer, **not** duplicate detection, app-cache cleaning or a claim that any file is unnecessary.
- Direct storage deletion currently removes files and **empty** folders only.
- Search, copy/move, multi-selection in Explorer, thumbnails and recycling bin are not yet implemented.
- Device testing is required beyond CI, especially for permission revocation, opening local files and permanent deletion.

## Settings & local error diagnostics

The **Settings** tab includes private, persistent error logging. Logs are enabled by default and limited to the 100 most recent entries. Each entry includes time, a fixed operation category and a broad failure category; raw exception messages, stack traces, filenames and file paths are intentionally excluded. The app records handled browsing/file-operation/cleanup errors and uncaught crashes (without swallowing the crash). Nothing is uploaded automatically.

Settings allows disabling future logging, creating a harmless **test log entry**, reviewing entries, **Copy**, **Share** (opens Android's share chooser only when tapped), **Export logs to .txt** (Android system Save dialog), and **Clear** after confirmation. Turning logging off does not delete existing entries; Clear removes stored history. All logs are stored in app-private preferences and are removed on uninstall. App backup is disabled.

### QA: Settings

- Open Settings and confirm the Error logging switch starts enabled and no old logs are unexpectedly present after fresh installation.
- Tap **Add test log entry**: a clearly marked Test entry appears with timestamp. Force-stop/reopen the app and verify it persists.
- Turn logging off and attempt another test event: no new entry appears. Turn it back on and retry.
- Tap Copy and paste into a temporary note, then tap Share and verify the Android share chooser appears (cancel without sending). Confirm that the exported text contains no private names or paths.
- Tap Clear and cancel (entries remain), then confirm Clear (history becomes empty). Reopen the app; history stays empty.
- Repeat in dark mode / small-screen portrait. Avoid deliberately crashing the device for testing.

### Export logs to a text file

Open **Settings → Error logging → Export logs to .txt**. The Android system **Save** dialog appears with a timestamped suggested file name, for example `MeowMeow-error-log-2026-11-18_15-47-00.txt`. Choose **Downloads** or any supported destination and tap **Save**. MeowMeow writes the same sanitized text you see through Copy/Share, in UTF-8, and shows a success/failure message. It uses `ACTION_CREATE_DOCUMENT` through Activity Result Contracts: no additional storage permission, direct filesystem path, automatic export or background upload is required. Cancelling the picker saves nothing. Existing log history remains intact after export.

**QA additions:** Create a test log entry, export to Downloads, inspect the resulting readable `.txt` and timestamps, verify no file paths/names or sensitive exception text appear, cancel a second export, verify the history remains unchanged, and check that tapping Export with no entries is disabled. Check a failed write reports a friendly error without crashing.

## Before the final device test

Follow the focused checklist in [`docs/FINAL_QA.md`](docs/FINAL_QA.md). Prior Files, Storage and Cleanup flows were reported as passing on a real device; the Settings / error log export still needs a final pass. Use only disposable files for any deletion checks.

### Stable diagnostics codes

Starting with the error-code update, each new entry includes a searchable, fixed `MM-<action>-<reason>` code and operation label. Examples: `MM-FS-REN-04` = direct-storage rename failed with an unavailable-operation condition; `MM-FS-MKDIR-04` = create-directory operation unavailable; `MM-SAF-DEL-01` = scoped-folder deletion denied. Reason suffixes: `01` permission, `02` I/O, `03` invalid operation, `04` unavailable, `99` unexpected; `00` test entry. No raw exception messages or file details are exported. Pre-update records remain intact and are labelled `MM-OLD-xx` (operation unknown); historical action cannot be reconstructed retroactively.

### Expected validation results

Duplicate names and invalid filenames during folder creation or renaming are **not recorded as errors**. They remain visible to the user as actionable validation feedback. A dedicated `ExpectedFileOperationException` distinguishes these cases from genuine I/O, permission or unexpected failures; `ErrorLogStore.record` ignores only the typed expected result (and normal coroutine cancellation). Older saved log entries are not rewritten or removed.

## MeowMeow app icon

The launcher now uses a custom MeowMeow cat-and-folder icon: a white smiling cat resting on a golden folder, with a purple paw print and purple background. The adaptive foreground is an Android vector drawable, not a bitmap, so it scales across device densities. Resources include adaptive launcher icons for Android 8+, a round icon alias, and a simplified Android 13+ monochrome resource for themed icons. No extra permission or app data changes are required.

## v0.2 development branch — search and file-type filtering

On `feat/file-tools-v0-2`, Files has a new **Search** icon. Search can filter by filename and file category, either in the current directory or recursively from the chosen storage root. Scans are read-only, run off the UI thread, can be cancelled, and are bounded to 2,000 folders / 20,000 items / 300 displayed results. Partial searches and unreadable folders are labelled. No file search history, index or file contents are stored. This feature is **pending real-device QA** and is **not merged into main**.

The full v0.2 scope and tests are in [docs/V0_2_FILE_TOOLS_PLAN.md](docs/V0_2_FILE_TOOLS_PLAN.md). Copy, bulk operations, duplicate detection and expanded large-file scanning remain planned; do not describe them as implemented.
