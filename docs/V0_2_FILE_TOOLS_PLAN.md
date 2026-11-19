# MeowMeow v0.2 — File discovery and operations

**Branch:** `feat/file-tools-v0-2`  
**Base:** `main` (merged, tested v0.1 MVP).  
**Status:** Planning/specification only. No features below should be marked implemented until code and real-device QA prove them. Keep this work in one draft PR until feature scope is reviewed. The separate `feat/advanced-tools` branch is independent, also based on `main`.

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

1. **Discovery milestone** — Search and file-type filtering, with pagination/progress, clear scope, cancellation and unit tests.
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
