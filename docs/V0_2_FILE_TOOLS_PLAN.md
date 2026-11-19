# MeowMeow v0.2 — File discovery and operations

**Branch:** `feat/file-tools-v0-2`  
**Base:** `main` (merged, tested v0.1 MVP).  
**Status:** Milestone 1 (filename search and file-type filtering) implemented in the draft PR, pending device QA. Other milestones remain planned. Keep this work in one draft PR until user review. The separate `feat/advanced-tools` branch is independent, also based on `main`.

## Requested features and acceptance criteria

### 1. Search (filename)
- Search filenames and folder names case-insensitively in a clearly indicated scope: current folder or selected root (recursive).
- Results show file type, size and relative location; tapping opens the file or parent folder.
- User can cancel a recursive scan. No network access, private search history or automatic indexing.
- Never traverse Android-protected folders, external volumes not granted, or symlinks outside a selected root. For SAF, search only within the chosen granted tree.
- Progress, permission loss, unreadable directories and partial-result limits are clearly presented; no false “all files scanned” message.

### 2. Find file type
- Filters: All, Images, Videos, Audio, Documents, Archives, APKs, Other, plus file extensions where meaningful.
- Uses MIME when available with cautious extension fallback; unknown/extensionless files appear in Other.
- Can be combined with search; show the active scope and number of matches.
- No content inspection is required to classify a file for the first version.

### 3. Find duplicates
- Exact-file duplicates, **not** similar photos or same-name files. First group by byte size, then stream a SHA-256 digest for possible matches.
- Duplicate results show groups, item sizes and estimated reclaimable space. A different path/name alone never proves duplication.
- Exclude directories, protected areas, symlinks and inaccessible files. Bound memory, support cancellation/progress and do not load entire files into memory.
- Detect files changed since scan and require a fresh validation before any deletion. Default action is **review only**; never select a “keep” or delete automatically.

### 4. Copy a file or folder
- User selects one source and a destination folder. Handle filename collisions without overwriting silently; default is **Skip**.
- A copied file must have the right bytes and the source must remain unchanged.
- For folders: preserve relative directory structure, skip unsafe symlinks and block destination inside source.
- Work only in approved device storage and/or SAF-granted trees. Clearly state when a selected provider cannot support a requested operation.

### 5. Bulk copy, bulk move, bulk delete
- Multi-select with Select all, Deselect, selected item count and estimated bytes. All operations require an explicit review screen.
- Copy is non-destructive; move removes the source **only after successful destination write and verification**; delete is permanent and must have a clear confirmation with exact selection count.
- Item-level results show succeeded, skipped and failed counts, with retry for failures and a safe cancelled/partial-completion state.
- Handle duplicate names, cross-folder and cross-provider destinations; block moves into self/descendant and any attempt to operate outside granted roots.
- Do not silently overwrite existing files, erase folders recursively without explicit review, or label a partially failed operation as successful.
- Normal user conflicts (duplicate names, invalid selection, permission declined) should not pollute Settings error diagnostics; genuine I/O errors should keep sanitized codes.

### 6. Large-file detection
- Build on current Cleanup (currently Downloads files ≥50 MiB); allow scanning a **user-selected scope**, changing the size threshold and sorting by descending size.
- Never delete automatically. Show file count, file sizes, cumulative selected total and scan progress.
- Require explicit review/confirmation for deletion, validating that files have not changed since the scan.

## Delivery order

1. **Discovery milestone (code implemented; device QA pending)** — Search and file-type filtering, with bounded results (up to 300), progress, clear scope, cancellation and unit tests.
2. **Safe copy milestone** — Single-file copy and folder copy, destination selection, collision handling, tests.
3. **Selection and bulk milestone** — Multi-select; bulk copy/move/delete with per-item progress, collision behavior and failure recovery.
4. **Analysis milestone** — Large-file detection beyond Downloads and exact duplicate detection (read-only first; deletion only through shared reviewed bulk flow).
5. **Final integration milestone** — Real-device regression and QA, fresh debug APK, updated docs, PR review and only then merge into `main`.

Implement incremental commits and keep the draft PR checklist honest. A feature is not “done” until applicable tests and a real Android device test pass.

## Cross-cutting safety and privacy

- No automatic deletion or overwrite; show explicit confirmations for destructive actions.
- All file scanning/copying/hashing must occur off the main UI thread, use streaming or bounded collections, provide cancellation and release resources.
- Avoid symlink traversal, path escaping, protected Android folders, and access outside explicitly granted SAF trees.
- No network requests, cloud uploads, private file paths in error logs, or persisted search/index without user agreement.
- Revalidate file existence/identity before writing, moving or deleting. Preserve source on failed move.
- Existing v0.1 features (Files, Storage, Cleanup, Settings diagnostics, icon) must continue to work.

## Real-device QA (mark on each milestone)

- [ ] Search current folder and recursively; verify matches, no matches, mixed case and rapid cancellation.
- [ ] File type filters handle extensionless/mismatched MIME, categories and search combinations.
- [ ] Duplicate detector finds byte-identical copies, ignores similar-name different-content files; cancellation and permission loss do not crash.
- [ ] Copy destination preserves bytes; source remains; duplicate-name conflicts do not overwrite.
- [ ] Bulk copy/move reports each item, handles interruption and does not delete source on failed copy.
- [ ] Bulk delete touches **only explicitly selected disposable test files** after a confirmation.
- [ ] Large-file scope/threshold match expected files; opening or scanning never deletes anything.
- [ ] SAF-only mode works inside selected tree, protected folders remain blocked.
- [ ] No private paths in Settings logs or exports; expected name collisions aren't recorded as errors.
- [ ] Build/test and Android debug APK succeed for PR head; regression checks pass on real device.

## Milestone 1 implementation details

Open **Files** and tap the **Search** icon in the top-right toolbar. A read-only search panel opens, offering:
- **Name contains (optional)**: case-insensitive filename/folder-name match; leaving blank applies type filtering without a filename requirement. To find a particular extension, enter text such as `.pdf`.
- **File type menu**: All types (includes folders), Images, Videos, Audio, Documents, Archives, APKs, Other (unrecognized/extensionless files). Uses MIME type when reliable, with known-extension fallback for generic or missing MIME.
- **Include subfolders**: unchecked scans the current folder only. Checked scans recursively from the selected device-storage root (full access) or chosen SAF root (folder picker). The scope is labelled in the UI.
- **Find files**: starts an on-demand scan on a background dispatcher; no saved index, search history, network access or automatic scanning.
- **Cancel scan** or back/close search: interrupts the coroutine, does not show a cancellation error, and does not change any files. During scanning the UI shows folders/items visited; once complete it shows results with relative location, type and size.
- **Tap result**: opens a file via the existing Android app chooser, or opens a found directory in the Files explorer with a valid folder breadcrumb route.

**Bounded search:** at most 2,000 folders, 20,000 visited items and 300 displayed results per scan; limit hits are labelled incomplete. Unreadable provider folders are counted and a partial-results warning appears. Scan results are in memory only and are discarded when search closes or the folder changes. Results are not proof that the entire phone has been indexed.

**Permissions:** Full-device scanning uses the existing checked shared-storage repository and skips symlinks/protected directories. SAF scans only children provided by the chosen tree provider. Do not infer access outside a granted tree.

### Milestone 1 device QA (still outstanding)
- [ ] Launch Files → Search from Device storage, then from a SAF-chosen folder. Both allow search and file-type filtering.
- [ ] Search `cat` / `CAT` and verify case-insensitive matching, nonmatching files excluded, folders included under All types.
- [ ] Switch the type category to Images and verify it excludes directories and PDFs; try Documents, APKs and Other, including extensionless files.
- [ ] With **Include subfolders** off, a nested file is absent. Turn it on and verify a nested match appears with relative location.
- [ ] Tap a matched folder to navigate into it and then Back; tap a matched file to open in another compatible app.
- [ ] Launch a large recursive search and tap Cancel; it stops and does not log `StandaloneCoroutine was cancelled`.
- [ ] Trigger a result limit or inaccessible folder (where safely possible) and ensure the UI labels results **incomplete** rather than claiming a complete search.
- [ ] Repeat with storage permission revoked: SAF continues to work inside its grant; no protected folders or symlink escapes are traversed.
- [ ] Verify scanning does not create, move, modify or delete any file; logging remains privacy-safe.
- [ ] Confirm search remains usable on a narrow portrait device and in dark mode.

