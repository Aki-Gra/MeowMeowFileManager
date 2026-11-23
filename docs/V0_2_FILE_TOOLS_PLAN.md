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
- User selects one source and a destination folder. On filename collisions, ask whether to **Keep both (Rename)**, **Replace** where atomic replacement is available, or **Skip**; never overwrite silently.
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
4. **Analysis milestone (code implemented, real-device QA pending)** — Large-file detection beyond Downloads and exact SHA-256 duplicates; read-only scans, no automatic deletion.
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
- A name collision now prompts **Keep both (rename copy)**, **Replace existing file** when a verified, atomic device-storage swap is supported, or **Skip this item**. Rename suggests a new, editable name preserving the extension. Replace is an explicit destructive choice, never the default. Folder replacements and SAF provider-based replacements are disabled because they lack a safe atomic swap; Rename or Skip remains available. Expected conflicts are not recorded as errors.
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
- [ ] Copy over a duplicate name: confirm the dialog offers Rename and Skip; where supported, Replace is explicit. Verify Rename leaves both files and preserves their contents. Verify Replace changes only the matching destination file after verification. Skip leaves everything unchanged and does not add an expected error to the log.
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

- **Copy:** Reuses streaming 64-KiB copy and SHA-256 destination verification. Each top-level filename conflict pauses the batch for explicit Rename, supported Replace, or Skip; never silently overwrite. Folder and SAF conflicts cannot replace.
- **Move:** Performs and verifies each source tree copy first, hashes for unchanged content and validates the source/destination structure **before removing original files**. Original files are deleted bottom-up and each directory must be empty first. If deletion is interrupted or fails, the destination copy remains and a per-item partial-failure warning tells the user to review *both* places.
- **Delete:** Explicit permanent review; bounded folder scan and source metadata revalidation before removal. Nonempty folders are traversed only during this confirmed bulk deletion; individual storage operations never recursively delete a directory automatically.
- **Folder constraints:** Operations are limited to 10,000 descendants and depth 48 for each selected top-level folder. Same-tree ancestry checks prevent folder copy/move into itself or descendants. Cross-mode file copy and move work only with valid grants; folder copy/move across different storage modes or selected roots is deliberately restricted.
- **Identity:** Selected file/folder type, size and modification time are checked again before processing. Symlinks and protected Android directories are excluded by existing checked storage adapters. SAF operations remain inside the user-granted tree.
- **Privacy:** Clipboard and selections are in-memory, never saved to disk or the network. Sanitized \`MM-BULK-COPY\`, \`MM-BULK-MOVE\` and \`MM-BULK-DEL\` codes are used for actual failures; expected conflicts and filename validation do not enter diagnostics.

### Milestone 3 real-device QA — required

- [ ] In Device storage choose **multi-select**, select two test files and a folder; check counters, row checkboxes, Select all, Deselect all and selection clearing on changing source folder.
- [ ] Choose Bulk Copy, navigate to another folder and choose Paste here; review number/names/destination, tap **Back** (no changes), reopen and confirm. Both original and copied files must match.
- [ ] Bulk-copy test files into a folder containing duplicate names. Verify each conflicting item prompts individually with Rename, Replace (only when safe), and Skip. Check the other items continue, outcomes are counted correctly, and existing files change only after explicit verified Replace.
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


## Post-device QA refinement — copy collisions (23 November 2026)

The user reported the Milestone 3 device test passed, with one UX request: **Copy must ask whether to Replace or Rename if a destination name already exists**, rather than automatically skipping the item.

**Implemented in code, new revision device QA still pending:**
- Single-copy **Paste here** and each top-level item in a **Bulk Copy** now interrupt on an existing name and show **Keep both (rename copy)**, **Replace existing file** where safe, and **Skip this item**.
- The suggested new name retains the original extension (report.pdf → report (copy).pdf), and can be edited. If another existing name is entered, the dialog asks again without overwriting.
- Direct-storage file Replace uses a separate staged copy, streamed SHA-256 verification of the staged copy, unchanged-destination validation and an atomic same-directory file swap. A failed/cancelled staged copy leaves the existing file unchanged. If atomic swap is not supported, Replace is rejected rather than falling back to unsafe truncation.
- Folder collisions cannot replace the entire existing directory. SAF document providers do not guarantee safe atomic replace; these cases show only Rename or Skip and explain why. A copy within its own source folder cannot replace itself.
- Bulk Move retains its existing **Skip on conflict** behavior. Bulk Copy is where per-item prompts are introduced; the dialog does not apply a destructive choice to the entire batch.
- If a user selects Skip, this is expected user feedback, not an error log event. Only genuinely failed operations produce privacy-safe diagnostic codes.

**Additional phone tests (use disposable items only):**
- [ ] Copy test.txt to a folder with a different test.txt. Choose Rename, then verify test (copy).txt and both originals open correctly.
- [ ] Repeat and choose Replace on device storage. Confirm the old destination content is replaced with the source content only after explicit confirmation.
- [ ] Cancel/interrupt a large Replace while staging. The old destination must remain readable and unchanged.
- [ ] Copy a file to its own folder. Replace must not be offered; Rename should create a second file.
- [ ] In Android folder-picker (SAF) mode and for folder-name conflicts, ensure Rename and Skip work and no unsupported Replace is available.
- [ ] Bulk-copy three disposable files, two with destination conflicts. Choose Rename for one and Skip for the other; ensure the third copies normally, per-item counts are accurate and unrelated files remain intact.
- [ ] Verify the dialog does not cover critical controls on a small phone and its Cancel/Skip option does not log an application failure.


## Milestone 4 — Exact duplicates and large files

**Code implemented, Android and real-device QA required.** Open **Cleanup → Find duplicates and large files**. This is separate from the pre-existing **Large files in Downloads (50 MiB+)** cleanup functionality.

- **Large files:** Choose 10, 50, 100, 500, or 1024 MiB minimum size. Results sorted largest first, with file names, sizes and relative folders.
- **Exact duplicates:** Group by byte size, hash size-matched files by streamed SHA-256 (64 KiB buffer). Files with identical names but different bytes are *not* duplicates. Groups display matched files and an estimated saving if **one** copy per group is kept.
- **Scopes:** Current folder only; Current folder + subfolders; Device storage root + subfolders, or Selected folder root + subfolders for the SAF picker. Current folder is the last folder opened in Files.
- **Progress/cancellation:** Scans run on Dispatchers.IO, can be cancelled or left via Back. Bound per scan: 2,000 folders, 20,000 visited items, 300 displayed results, and 20 GiB of duplicate hash reads. Limits and unreadable/changed files are explicitly marked incomplete.
- **Read-only/privacy:** Results live in memory only. No filesystem changes or automatic delete, no network/index persistence. Existing Android permissions and symlink/protected-folder safeguards apply; sanitized MM-DUP-SCAN / MM-LARGE-SCAN diagnostics without filenames, paths or raw exceptions.
- Tap a file to use the existing file opener. For deletion, review files manually through the Files tab; never delete every copy of a duplicate set blindly.

**Device QA checklist:**
- [ ] In Files open a nested folder, then Cleanup → Find duplicates and large files. Test all three scopes: current subtree must exclude siblings; root scope must include accessible siblings.
- [ ] Large-file thresholds exclude smaller files; results are size-sorted; test empty and mixed-size folders.
- [ ] Two byte-identical files with different names form one duplicate group; an identically named but different-content test file does not.
- [ ] Test a matching file in a subfolder: absent in shallow scan, present in recursive scan. Verify potential space savings is clearly an estimate.
- [ ] Scan using SAF-only access in a chosen tree, and with full-device access. Android-protected folders and symlinks must not be searched.
- [ ] Cancel a long scan; also use Android system Back and navigate to another tab while scanning. No crash or false error log.
- [ ] Inaccessible files or hitting limits produce incomplete-result warnings. A stale or changed file is not called a verified duplicate.
- [ ] Tap results to open files and verify neither scanner creates, changes or deletes anything.
- [ ] Regression: existing Downloads cleanup, Search and its scopes, ordinary and bulk copy/move/delete, Replace/Rename/Skip, Storage and privacy-safe diagnostics.
- [ ] Check narrow portrait and dark theme. Test the APK built from the matching PR head before accepting Milestone 4.
