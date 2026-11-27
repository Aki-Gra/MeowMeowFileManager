# MeowMeow File Manager 🐾

A native Android file explorer built in Kotlin and Jetpack Compose.

## Screens

- **Files:** Browse shared storage after explicit All files access approval, or use Android's folder picker as a scoped fallback. Browse Downloads directly, preview supported documents inside MeowMeow, sort, create folders, rename and delete after confirmation.
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

### Explicit search locations

Search has three choices: **Current folder only**, **Current folder + subfolders**, and **Device storage root + subfolders** (labelled **Selected folder root + subfolders** when using the Android folder picker). The previous **Include subfolders** toggle has been removed because it misleadingly searched from the root rather than the current directory.


## v0.2 milestone 2 — Copy (device QA pending)

On the v0.2 feature branch, Files → item menu → **Copy** selects a file or folder. Navigate within Files and choose **Paste here**. Copies preserve the original source. If a destination name exists, MeowMeow now asks to **Keep both (rename copy)**, **Replace existing file** where safe, or **Skip**. Replacements on direct storage are explicitly confirmed, SHA-256 verified in a separate staged file, then atomically swapped; folder and SAF replacements are disabled for safety. An interrupted staging copy attempts to remove only what it created. Single files can cross direct/SAF access modes where permission allows; folder copy is restricted to the same storage mode and selected root. See [v0.2 plan](docs/V0_2_FILE_TOOLS_PLAN.md) for limits and QA steps. The updated conflict UX is pending real-device QA and is not yet merged into main.


## v0.2 Milestone 3 — bulk operations (device QA pending)

The Files checklist button enters multi-select mode for files and folders in the current folder. **Select all**, **Deselect all**, **Copy**, **Move**, and **Delete** are available, with an explicit confirmation/review step before processing. Copy/move choose a destination via the existing explorer; delete is permanent and warns about selected folder contents. Processing is sequential with success/skip/failure/remaining counts and cancellation. Moves delete originals only after a verified copy; unexpected partial results must be reviewed. All operations respect storage permissions and never silently overwrite. See [the milestone 3 QA checklist](docs/V0_2_FILE_TOOLS_PLAN.md).


### Copy collision prompts (new build — pending phone verification)

On **single Copy** and **Bulk Copy**, a duplicate filename opens a choice for **Keep both (rename copy)**, **Replace existing file** (only for direct storage regular files with atomic replacement), or **Skip this item**. Rename preserves extensions by default and can be edited. Replace is never automatic, and existing content remains intact if staging fails before the atomic commit. SAF providers and folder collisions support Rename/Skip only. Bulk Move still skips destination conflicts rather than overwriting originals. No personal file paths/names are recorded in the error log.


## v0.2 Milestone 4 — File analysis (device QA pending)

**Cleanup → Find duplicates and large files** provides two read-only scans: large files with a 10 / 50 / 100 / 500 / 1024 MiB minimum, and exact duplicates verified by size and streamed SHA-256. Both support current folder, current subtree, or selected storage root subtree, direct shared-storage or SAF-granted access, progress, cancel, and incomplete-result warnings. Every scan is limited to 2,000 folders, 20,000 entries, and 300 displayed matches; hashing has a 20 GiB budget. No files are automatically removed and no private paths/content/hashes are persisted or logged. Existing Downloads cleanup is unchanged. Device QA is required before merge.


### Remove duplicates directly (device QA pending)

Each exact-duplicate row now has **Delete**, and groups have checkboxes plus **Select all copies** and **Delete selected**. Both one-copy and all-copies deletion require explicit permanent confirmation, with a stronger warning when no copy will remain. Before removing each file, MeowMeow revalidates its size, identity, name and SHA-256 content against the scan. Changed files are skipped; completed deletions remain permanent. Results update and rescan is recommended. No deletion is performed merely by scanning.


### Analysis screen scrolling on small phones (device QA pending)

The Cleanup analysis page now scrolls as a **single list** from scan settings to results, rather than keeping a large non-scrolling header above a small independently scrolling results area. On completion it scrolls to the results summary. A top-bar settings shortcut returns to the scan controls, and confirmed duplicate-deletion progress stays visible at the bottom while scrolling.


## Advanced document reader (v0.3 preview PR)

This branch integrates the in-app reader with current v0.2 Files / Search / Analysis.
File taps from both the device storage and SAF folder picker open a **MeowMeow-owned
offline reading screen first**, not an external browser/reader. "Open with…" is
available only as an explicit user action.

- PDF: Android PdfRenderer displays pages with Next/Previous and zoom.
- TXT, JSON, CSV, XML, Markdown, YAML, log files and similar text: scrollable,
  selectable, read-only contents with font controls and BOM detection.
- DOCX, ODT: local, bounded extraction of readable text. No pixel-perfect layout.
- Legacy DOC: read-only text extraction via Apache POI HWPF; requires device QA.
- Unsupported files: safe in-app explanation with optional external opening.
- No network activity, auto-opening external apps or edits to source documents.
- Bounded processing and private temporary caching limit parser and memory usage.

The reader is integrated on a branch derived from merged v0.2 changes without
overwriting basic-file tools. Verify it using [reader QA](docs/ADVANCED_READER_QA.md)
and the existing v0.2 testing plan before merging PR #5.

### In-app image viewing

The advanced document reader also previews common images inside MeowMeow: JPEG/JPG, PNG, WebP, GIF (still first frame), BMP, plus HEIC/HEIF and AVIF where Android supports them. Images fit the screen initially, with touch pinch/pan and zoom/reset buttons. Decoding happens locally off the main thread at a bounded preview resolution, without storing or modifying originals. Older Android versions may not decode modern formats; corrupt/unsupported files show a friendly in-app failure and an explicit **Open with…** option. No external viewer is launched automatically.


## Experimental file-view layouts (stacked preview branch)

On \`experiment/explorer-icons-thumbnails\` (based on the advanced reader branch),
the **Files** view has a **Choose file view** control near Sort, offering
**List**, **Large icons**, and **Thumbnails**. List is the default and preserves
existing behavior; icon and thumbnail modes use lazy adaptive grids, retaining
folder navigation, sorting, copy/rename/delete and bulk selection. JPEG/PNG/
WebP/GIF and other locally decodable images show capped thumbnails; other
file types show large icons. A small 8 MiB in-memory cache is used for visible
tiles only; no persistent thumbnail cache, network calls or file modification.
The selected mode is stored as a single non-sensitive app preference. Search
results intentionally remain in the existing list to show match locations.

This is experimental: test performance, readability, small-screen layout,
SAF access and actions before merging it into the reader PR. See
[docs/EXPERIMENT_FILE_VIEWS_QA.md](docs/EXPERIMENT_FILE_VIEWS_QA.md).

## Share original files (v0.3)

The in-app PDF/text/image viewer has an explicit **Share** action beside **Open with…**. **Files → three-dot menu → Share** also works for regular files in List, Large icons and Thumbnails layouts (including files the reader cannot preview). No folder sharing or automatic sharing is supported.

Sharing launches Android's native Sharesheet with `ACTION_SEND`, `EXTRA_STREAM`, a grantable `content://` URI and temporary read-only permission. Direct shared-storage files are converted through the app's existing FileProvider; Storage Access Framework files retain their provider content URI. Supported MIME types are preserved or inferred from the extension for generic/unknown provider MIME values. **The original file is shared unchanged**, not an extracted text preview, PDF screenshot or image thumbnail. The user chooses a destination; canceling the sheet changes nothing. Share failures use sanitized `MM-SHARE-FILE` diagnostic codes without paths, filenames or exception text. No background upload or new storage/network permissions are added.


## Open with MeowMeow from other Android apps

MeowMeow can be offered in Android's **Open with** chooser for `content://` document URIs with MIME types it knows how to preview:
PDF, DOC/DOCX, ODT; supported JPEG/PNG/WebP/GIF/BMP/HEIC/HEIF/AVIF images;
all `text/*` text, source/code and Markdown files; JSON, XML, CSV,
YAML, TOML, JavaScript and similar text formats. Android's chooser, not
MeowMeow, controls the displayed choices and defaults.

An exported routing activity validates the incoming file and forwards a
temporary read grant to the **non-exported** ReaderActivity. The router
finishes immediately; Back from the reader returns to the origin rather
than launching MeowMeow's main file list. The app does not ask for All files
access just to read an external `content://` file. Share and Open with
stay available inside the reader, and the Open with chooser excludes
MeowMeow itself to prevent an open loop.

**Limits:** Only provider-backed `content://` URIs are registered for security.
Unsupported formats and generic `application/octet-stream` MIME types
are not advertised: for those sources, Android may not show MeowMeow even
if the file's extension could otherwise be read. Browsing an HTTP URL
directly in MeowMeow is not part of this feature. Refer to
[external-open QA](docs/ADVANCED_READER_QA.md#9--open-files-in-meowmeow-from-other-android-apps).


## Swipe navigation — image gallery and PDF pages

When an image is opened **from MeowMeow's Files folder or search results**,
the reader receives a temporary, in-memory snapshot of nearby images in the
current result order. Swipe **right-to-left for the next image**, and
**left-to-right for the previous image**; optional Previous/Next buttons and
the photo counter provide accessible alternatives. Non-image files and folders
are skipped. The session is never persisted and is cleared when the reader
closes. Very large sets are safely windowed to 5,000 items around the
selected photo. Images opened **from other apps** have no folder context, so
there is no adjacent-image navigation.

PDF pages support the same horizontal gestures at fit-to-screen size while
retaining existing Previous/Next buttons. When a PDF is zoomed beyond 100%,
horizontal dragging pans the page instead. On images, pinching or panning
while zoomed does not switch to another image. A left/right gesture must
exceed a deliberate movement threshold and beat vertical movement to avoid
unintended page changes.

No images are uploaded, indexed, altered, or cached to disk by the gallery
navigation feature. Sharing and external opening always use the image
currently displayed.


## PR #5 hardening after deep review

The reader now limits **inflation of every Office ZIP part**, including
parts skipped before the document XML, so highly compressed archives
cannot bypass the overall budget. Known, specific provider MIME types
take precedence over misleading extensions. External provider metadata
is queried on a background I/O dispatcher.

The PDF reader reuses one synchronized native renderer while switching
pages and keeps at most its current rendered preview in the UI, with
explicit bitmap and private temporary-copy cleanup. Only non-seekable
PDF descriptors use the bounded private copy fallback, not every invalid
document. Image sources with unknown sizes use a bounded temporary
copy, removed immediately after preview decoding; thumbnail grids show
icons for unbounded unknown-size images instead of trying large reads.

All file-list display modes use one shared actions menu. Gallery context
is also passed from analysis results. If Android kills the app while
a gallery reader is open, the photo still has a standalone preview but
its ephemeral image list is not restored; the UI explains how to reopen
the gallery. This deliberately avoids persisting private file lists.

The build runs JVM unit tests and compiles an Android instrumentation
APK; execute \`./gradlew :app:connectedDebugAndroidTest\` on a device
to exercise DOCX/ODT parsing, PDF renderer reuse and actual Android
image decoding. See [device QA section 11](docs/ADVANCED_READER_QA.md#11--securityperformance-regression-after-deep-code-review).

**Signing:** Automated debug builds remain disposable and may have
different certificates. Official releases use a separately initialized
permanent signing key, with only an encrypted backup and checksum committed
directly to main during explicit key initialization. Never commit a plaintext private signing keystore or passwords.

## Signed Android releases

A separate GitHub Actions release workflow assembles a verified,
signed release APK using the two repository secrets `SIGNING_STORE_PASSWORD`
and `SIGNING_BACKUP_PASSPHRASE` and an encrypted permanent signing key. Its manual run can first build
a signed test artifact, and then publish versioned GitHub Releases.
See [release setup and signing requirements](docs/SIGNED_RELEASES.md).

The repo is private, so Release downloads require repository access.

The optional `SIGNING_ENCRYPTED_KEYSTORE_BASE64` secret is supported for
recovering the *same* encrypted backup, with its SHA-256 pinned to the
already-initialized MeowMeow signing identity. A mismatched backup is rejected.

## Automatic signed Beta releases

Application or build changes on `main` trigger a two-job signing workflow:
unit tests, Android lint and unsigned APK compilation run without secrets;
a separate signing job checks the permanent MeowMeow certificate and
publishes a versioned GitHub Beta Release with an APK and SHA-256 checksum.
The repo remains private, so assets require repository access. See
[automatic release guidance](docs/AUTO_RELEASES.md).
