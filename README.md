# MeowMeow File Manager 🐾

An offline Android file manager built with Kotlin and Jetpack Compose. Android 8.0+ (API 26).

## Download

Get the latest signed Beta APK and checksum from [GitHub Releases](../../releases). Install or update manually using Android. APKs signed before the 27 November 2026 key rotation, or debug builds, may need to be uninstalled first (this removes app settings).

## Features

- **Files:** Browse primary shared storage with All files access on Android 11+, or use Android's Storage Access Framework (SAF) folder picker. Browse Downloads, sort, create folders, rename and delete.
- **Views:** List, large icons and image thumbnails; chosen layout is remembered.
- **Search:** Filter names and file types in the current folder, its subtree or the storage root. Search is cancellable and bounded; incomplete results are indicated.
- **File operations:** Copy files and folders, bulk copy/move/delete, select all/deselect all, confirmation and progress. Copy conflicts offer safe rename/skip and supported atomic file replacement.
- **Storage and cleanup:** Storage overview, Downloads cleanup (50 MiB+), configurable large-file scans and SHA-256 duplicate detection. Explicit confirmation and revalidation before deletion.
- **Tasks:** In-app background task panel, cancellation, conflict protection and completed-result reopening. No automatic task queuing.
- **Readers:** Offline PDF, text, DOC/DOCX/ODT text extraction and supported images; page/gallery swipe, zoom, share original files and explicit Open with. Supported documents can also be opened from other Android apps.
- **APK installation:** Tap a local APK to open Android's native package installer. If needed, enable Install unknown apps for MeowMeow in Android settings and tap again. Android controls confirmation, validation and installation; only install packages you trust.
- **Settings:** Optional private error logging with sanitized categories, copy/share/export and clear.

## Permissions, privacy and limitations

- All files access is optional; SAF remains available. Protected Android directories and root access are not supported. Direct browsing covers primary shared storage, not all removable volumes.
- Install unknown apps permission is used solely for explicitly selected APKs; no silent installation, remote downloads or automatic updates.
- No network permission, analytics, automatic cloud upload or persistent file-content index.
- Permanent deletion; no recycle bin. Review selected items before deleting.
- Search result management actions are not yet available (tracked in [#12](../../issues/12)).
- Office previews extract text rather than reproducing document layout. GIF preview is static; modern image formats depend on device decoding support. Reader and scan size limits protect memory.
- Copy/move restrictions depend on storage access, provider capabilities and safe conflict handling.
- Google Play distribution would require compliance with applicable All files access and package-installation permission policies.

## Build and test

1. Check out `main` (or a PR branch for testing).
2. Use Android Studio, JDK 17 and Android SDK Platform 37.
3. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on a QA device.

GitHub Actions builds and tests pull requests. Physical-device QA is required before merging; use disposable files for destructive tests. Instrumentation tests can be run on a connected device with `./gradlew :app:connectedDebugAndroidTest`.

## Architecture

- `MainActivity.kt`: app navigation, permission handling, file opening and task overlay.
- `data/`: direct and SAF storage repositories and copy operations.
- `model/`: file discovery, analysis, safe copy, bulk operations and task scopes.
- `ui/`: Compose screens, view models and task coordination.
- `reader/`: bounded local document/image readers and gallery sessions.
- `sharing/`: temporary read-only content URI grants.
- `diagnostics/`: private sanitized error logging.

Blocking filesystem and reader processing is dispatched off the UI thread.

## QA and release documentation

- [Core device QA](docs/FINAL_QA.md)
- [File tools and regression plan](docs/V0_2_FILE_TOOLS_PLAN.md)
- [Background task coordination](docs/V0_2_BACKGROUND_TASK_EXPERIMENT.md)
- [Explorer layouts](docs/EXPERIMENT_FILE_VIEWS_QA.md)
- [Reader QA](docs/ADVANCED_READER_QA.md)
- [Automatic signed Beta releases](docs/AUTO_RELEASES.md)

The protected `main` branch publishes signed Betas automatically for app/build changes. PR builds do not use release signing secrets. Historical development plans remain available in the docs directory.
