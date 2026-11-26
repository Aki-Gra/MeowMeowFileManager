# v0.2 experiment — background task coordination

**Isolation:** `feat/v0-2-background-task-coordination` branches from `feat/file-tools-v0-2` (PR #3) and is reviewed via **draft PR #6 targeting v0.2**, not `main`. Do not merge without phone testing and approval.

## Behaviour

1. **Task notifications across tabs:** Searches (device and SAF filename search, duplicate scan, large-file scan, Downloads scan) and confirmed copy, bulk, duplicate-delete and Downloads-delete jobs are tracked in memory by an activity-scoped `TaskCenterViewModel`. Task progress and outcomes are available from the small notification icon in the bottom-right of the app. Tap to expand, tap **Open** to return to the relevant screen, or explicitly **Stop** a running task. The icon can be collapsed without cancelling anything.
2. **Conflicts:** File-changing actions (create, rename, delete, copy, move, bulk operations, duplicate-delete and Downloads cleanup) check the active task registry. Starting a new scan checks existing file-changing tasks. Device storage locations are compared by component-boundary directory ancestry; operations on unrelated device trees can proceed independently. SAF document IDs can be opaque and different grants can alias each other; SAF/unknown relationships are conservatively treated as conflicts.
3. **Conflict dialogue:** **Keep tasks** cancels only the newly requested action; **Stop tasks** requests cancellation of the conflicting active jobs and discards the newly requested action. The user must manually retry when task status has changed from *Stopping* to *Stopped*. **No automatic Continue, queue or retry.** If the task is still stopping, another conflicting request remains blocked.
4. **Permission changes:** Switching SAF grants, opening management for full-file access and storage permission controls is guarded while tasks run. Permissions changed by Android outside the app cannot be pre-confirmed; permission revocation requests cancellation of affected jobs and displays an explanation.
5. **Outdated results:** Mutations affecting completed scan locations mark their tasks *Outdated*. Search and analysis screens show a rescan warning. A new scan is opt-in. Exact duplicate deletes continue to rehash files before permanent removal.
6. **File-changing task safety:** Confirmed writes are tracked rather than discarded on navigation; cancellation occurs through their existing safe-point/cleanup handling. Completed file changes remain and are never undone by a tab switch.

Tasks are **not persisted**, shared over a network or resumed after the Android app process is killed. Task metadata shown in the panel contains generic action names and item counts; filenames, private paths and hashes are not placed into persistent error logs.

## Required phone QA — disposable files

- [ ] Start a slow duplicate scan of a test folder; switch to Files, Storage, Settings and back to Cleanup. The scan continues and progress/results are available on return.
- [ ] Collapse the task window into the little notification icon. Expand it; ensure counts/statuses update, tap **Open**, then dismiss a completed notification.
- [ ] Start a Files filename search and change tabs. Return from Tasks; search is still running or retains completed matches. Closing Search or tapping Stop explicitly cancels it.
- [ ] Scan test folder A, then try deleting/renaming A or a child in Files. **Conflict warning** appears; **Keep tasks** does not change files.
- [ ] Repeat and select **Stop tasks**. Verify task becomes *Stopping*, then *Stopped*. The delete/rename **does not happen automatically**. Manually try again afterward.
- [ ] Scan folder A and delete an unrelated file in folder B. It should proceed without a warning.
- [ ] Try **Create folder**, copy paste, bulk delete/move, and duplicate delete against a path used by a current scan. The same conflict rules apply before destructive operations.
- [ ] Start an active copy/move and attempt an overlapping search, rename or permission change. The ongoing write remains tracked; conflicts block the new operation. Stopping a write must use existing safe-point cancellation and state must show completed items.
- [ ] Try a SAF scan and change grants or request a storage permission change. Unknown SAF relationships should produce a warning rather than assuming unrelatedness.
- [ ] Revoke full-file access externally during a device scan. The scan stops safely and the notification explains permissions have changed.
- [ ] Complete a scan, then modify a matching file or folder. Results show *Outdated* and **Scan again**; the scan does not restart automatically.
- [ ] While a scan is stopping or holding a slow ContentResolver stream, retry a conflicting delete. It must stay blocked until the job really completes.
- [ ] Retest Copy conflict Rename/Replace/Skip, exact duplicate deletion (including selecting all copies), Downloads cleanup, bulk operations, privacy-safe error logs, scrolling layout and narrow phones.

## Automated tests

`TaskScopeTest` verifies component boundaries, nested paths and conservative SAF/unknown relationships. `TaskCenterViewModelTest` verifies unrelated operations, explicit conflict choices, no queuing, stop-state blocking, outdating and permission-revocation handling. Existing file-operation unit tests also run under Android CI.

## Limitations for this experiment

- One in-progress scan per search source/analysis mode ViewModel at a time; initiating a new scan in the same ViewModel replaces its previous scan.
- SAF tree relations are intentionally conservative; some unrelated SAF operations will show a warning until provider-specific safe disjointness can be proven.
- These are in-process jobs, **not** persistent Android background services. Some provider calls cannot be interrupted immediately, so *Stopping* is shown until they return and release file handles.

## Small-screen floating notification position (26 Nov 2026)

The collapsed task-notification icon and expanded panel are anchored **bottom-left**, opposite the Files screen's New Folder control. This avoids covering the primary creation action without introducing drag gestures that could interfere with scrolling and tap targets. All task and conflict-dialog behavior is unchanged. Device QA: open Files on a small portrait phone, confirm New Folder is unobstructed while the bell is visible, then expand/collapse and switch between tabs.
