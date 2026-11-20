# MeowMeow v0.2 — File discovery and operations

**Branch:** `feat/file-tools-v0-2`  
**Base:** `main` (merged, tested v0.1 MVP).  
**Status:** Milestone 1 (filename search and file-type filtering) implemented in the draft PR, pending device QA. Other milestones remain planned. Keep this work in one draft PR until user review. The separate `feat/advanced-tools` branch is independent, also based on `main`.

## Requested features and acceptance criteria

### 1. Search (filename)
- Search filenames and folder names case-insensitively with three explicit scopes: current folder only; current folder and its subfolders; selected storage root and its subfolders.
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
2. **Safe copy milestone (implemented in code; device QA pending)** — Single-file and folder copy, in-explorer destination navigation, collision handling, SHA-256 verification, cancellation/rollback, automated tests.
3. **Selection and bulk milestone (implemented; device QA pending)** — Multi-select; bulk copy/move/delete with per-item progress, collision behavior and failure recovery.
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
- **Search location**: explicit menu with **Current folder only**, **Current folder + subfolders**, and **Device storage root + subfolders** (or **Selected folder root + subfolders** for SAF). Choosing current-folder recursive scope never silently moves the scan to the root.
- **Find files**: starts an on-demand scan on a background dispatcher; no saved index, search history, network access or automatic scanning.
- **Cancel scan** or back/close search: interrupts the coroutine, does not show a cancellation error, and does not change any files. During scanning the UI shows folders/items visited; once complete it shows results with relative location, type and size.
- **Tap result**: opens a file via the existing Android app chooser, or opens a found directory in the Files explorer with a valid folder breadcrumb route.

**Bounded search:** at most 2,000 folders, 20,000 visited items and 300 displayed results per scan; limit hits are labelled incomplete. Unreadable provider folders are counted and a partial-results warning appears. Scan results are in memory only and are discarded when search closes or the folder changes. Results are not proof that the entire phone has been indexed.

**Permissions:** Full-device scanning uses the existing checked shared-storage repository and skips symlinks/protected directories. SAF scans only children provided by the chosen tree provider. Do not infer access outside a granted tree.

### Milestone 1 device QA (still outstanding)
- [ ] Launch Files → Search from Device storage, then from a SAF-chosen folder. Both allow search and file-type filtering.
- [ ] Search `cat` / `CAT` and verify case-insensitive matching, nonmatching files excluded, folders included under All types.
- [ ] Switch the type category to Images and verify it excludes directories and PDFs; try Documents, APKs and Other, including extensionless files.
- [ ] From a nested folder, compare all three Search location choices: current only excludes nested files; current + subfolders finds only the current subtree; device/selected root + subfolders also finds matching files outside the current subtree. Check both direct storage and SAF.
- [ ] Tap a matched folder to navigate into it and then Back; tap a matched file to open in another compatible app.
- [ ] Launch a large recursive search and tap Cancel; it stops and does not log `StandaloneCoroutine was cancelled`.
- [ ] Trigger a result limit or inaccessible folder (where safely possible) and ensure the UI labels results **incomplete** rather than claiming a complete search.
- [ ] Repeat with storage permission revoked: SAF continues to work inside its grant; no protected folders or symlink escapes are traversed.
- [ ] Verify scanning does not create, move, modify or delete any file; logging remains privacy-safe.
- [ ] Confirm search remains usable on a narrow portrait device and in dark mode.


### Scope selector clarification

The misleading **Include subfolders** switch was replaced with a **Search location** selector. The three options determine both the starting folder and recursion. Default: **Current folder only**. Changing the location, file-type filter or query clears stale results; users must tap **Find files** again. Device mode labels the root as **Device storage root**; SAF mode labels it **Selected folder root** so it never suggests permission to search outside the chosen tree.

- [ ] Search inside a deeply nested folder: **Current folder + subfolders** must never return matches in sibling folders.
- [ ] **Root + subfolders** must include permitted sibling locations and preserve usable navigation to matched folders.
- [ ] **Current folder only** must match just direct children.
- [ ] Change location with results showing; old results clear before the next scan. Confirm all 3 choices fit on a narrow phone screen.


## Milestone 2 — single file and folder copy (code implemented, device QA pending)

**How to use:** Open **Files**, tap an item's **⋮** menu and choose **Copy** (including read-only source items). Navigate to the folder where the copy should go, then tap **Paste here** in the persistent copy bar. The bar shows the source name, progress and **Discard** / **Cancel copy**.

**Behavior and safety**
- Copy, not move: **the original item is never deleted**. File bytes stream through a fixed 64 KiB buffer off the UI thread; files are verified by SHA-256 against destination read-back. No entire file is loaded into memory.
- Default conflict policy is **do not overwrite**. If the same name exists at the destination, the app shows a validation message; the existing file remains unchanged and no expected-conflict diagnostic entry is created. Choose a different destination or discard.
- Folder copies preserve their relative directory structure, with limits of 10,000 entries and depth 48. Self/descendant destinations are prohibited.
- The app tracks every newly created item and cleans up only those items, in reverse order, if copying is cancelled, verification fails or another error occurs. If cleanup cannot finish, the app warns that an incomplete copy may remain.
- Single-file copies can cross between full-device and SAF modes, provided both are authorized. **Folder copies require the same storage mode and the same selected root**, avoiding ambiguous directory ancestry and accidental self-copy across Android providers. This is a deliberate first-stage restriction.
- All storage operations revalidate access when executed. Full-device paths are restricted to primary shared storage and must not be symlinks; SAF only uses provider handles from previously granted trees.
- Clipboard stays in memory for the lifetime of the app process, and is not persisted or shared. The app never transfers any files over the network.
- Genuine copy failures use sanitized diagnostic operation codes; common duplicate-name and invalid-destination cases are actionable validation messages instead.

**Device QA — mandatory before milestone is accepted**
- [ ] Copy a disposable TXT, PDF and image into another folder in **Device storage**; confirm the original remains and the new file opens with identical content.
- [ ] Copy a small folder with nested subfolders and several files; confirm folder hierarchy and bytes are preserved.
- [ ] Use **Choose another folder** (SAF) and copy a file within the granted tree. Copy a file between SAF and Device storage in both directions where Android permits it.
- [ ] Attempt to paste where an item with that name already exists. It must never overwrite/replace the existing item, and it must not add an expected error to the log.
- [ ] Try to paste a folder into itself or one of its descendants. The app must refuse without creating files.
- [ ] Select a large disposable file, start copying, tap **Cancel copy**. Check no partial destination item is left; original remains intact.
- [ ] Revoke permission during copy or simulate a provider write failure with test files. Copy must fail safely and report if any incomplete item could not be cleaned.
- [ ] Copy a read-only source to a writable destination. If the source is not readable, show a friendly failure without changing it.
- [ ] Check the copy bar, button disabled state, progress, back navigation, small portrait phone and dark mode.
- [ ] Confirm Settings logs do not contain file names, full paths, stack traces or raw I/O exception messages.
- [ ] Test previous Search, Storage, Cleanup, rename, delete and Settings features for regressions.

## Milestone 3 — Multi-selection and reviewed bulk operations

**Status:** Implemented in the v0.2 branch; automated verification and **real-device QA pending**. No automatic file removal or overwriting is performed without an explicit action.

### Workflow

1. In **Files**, tap the multi-select **checklist** icon. Check individual entries or choose **Select all** / **Deselect all**. A selection is limited to the current visible folder and 500 top-level entries, and is cleared if you leave that source folder while still selecting. Known selected file size is shown, explicitly excluding sizes inside folders.
2. Choose **Copy**, **Move**, or **Delete** from the selection footer.
3. For Copy/Move, navigate to a writable folder in Device storage or the granted SAF tree, then tap **Paste here**. The review dialog shows destination, number of selected items and a sample of their names. **Back** returns to destination selection without changing any files.
4. For Delete, a review dialog warns that selected folders and their full contents are **permanently removed** with no recycle bin or undo.
5. Only after confirmation does processing begin. Operations run sequentially, off the UI thread, with item-by-item results. **Cancel remaining** stops before the next safe step; already completed actions are retained and explicitly reported.
6. The results footer displays successful, skipped, failed and unprocessed counts and the first five non-success outcomes. Nothing is marked all-success when individual items fail.

### Safety and scope

- **Copy:** Reuses streaming 64-KiB copy and SHA-256 destination verification. Name collisions skip the item and do not overwrite.
- **Move:** Performs and verifies each source tree copy first, hashes for unchanged content and validates the source/destination structure **before removing original files**. Original files are deleted bottom-up and each directory must be empty first. If deletion is interrupted or fails, the destination copy remains and a per-item partial-failure warning tells the user to review *both* places.
- **Delete:** Explicit permanent review; bounded folder scan and source metadata revalidation before removal. Nonempty folders are traversed only during this confirmed bulk deletion; individual storage operations never recursively delete a directory automatically.
- **Folder constraints:** Operations are limited to 10,000 descendants and depth 48 for each selected top-level folder. Same-tree ancestry checks prevent folder copy/move into itself or descendants. Cross-mode file copy and move work only with valid grants; folder copy/move across different storage modes or selected roots is deliberately restricted.
- **Identity:** Selected file/folder type, size and modification time are checked again before processing. Symlinks and protected Android directories are excluded by existing checked storage adapters. SAF operations remain inside the user-granted tree.
- **Privacy:** Clipboard and selections are in-memory, never saved to disk or the network. Sanitized \`MM-BULK-COPY\`, \`MM-BULK-MOVE\` and \`MM-BULK-DEL\` codes are used for actual failures; expected conflicts and filename validation do not enter diagnostics.

### Milestone 3 real-device QA — required

- [ ] In Device storage choose **multi-select**, select two test files and a folder; check counters, row checkboxes, Select all, Deselect all and selection clearing on changing source folder.
- [ ] Choose Bulk Copy, navigate to another folder and choose Paste here; review number/names/destination, tap **Back** (no changes), reopen and confirm. Both original and copied files must match.
- [ ] Copy into a folder containing a duplicate name. It must be **skipped** without modifying the existing file, with accurate success/skip counts and no false error log entry.
- [ ] Bulk Move several disposable files to another folder; verify destination content and removal of *only* successfully copied originals. Move a nested test folder and verify its hierarchy.
- [ ] Use folder-picker mode (SAF) for selection/destination and check operations respect its grant. Try cross-mode file copy/move with disposable files. Confirm cross-mode **folder** move is refused safely.
- [ ] Select disposable files and a disposable folder with nested content, choose Bulk Delete, cancel the review first (no deletion), then confirm; only selected data is permanently removed.
- [ ] During a long bulk copy or move tap **Cancel remaining**; check completed items remain, the current copy cleans up if possible, and the unprocessed count is correct. Never test on personal files.
- [ ] Revoke storage permission or make a disposable destination read-only before execution; verify failure handling and that originals are not lost on failed copies.
- [ ] Confirm move-to-self, move-to-descendant and duplicate destination names are safely refused or skipped.
- [ ] Verify UI on a narrow portrait display and dark mode; review dialogs must scroll for many selected items.
- [ ] Check that genuine failures have sanitized operation codes and that personal filenames, paths, contents and stack traces are **not in exported error logs**.
- [ ] Regression: Search scope, file type filters, ordinary single-file Copy/Paste, rename/delete, Storage, Cleanup, Settings and the launcher icon still work.
- [ ] Current PR head passes unit tests / Android assembleDebug and a matching APK is installed before recording final on-device acceptance.
