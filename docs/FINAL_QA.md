# MeowMeow MVP — final real-device QA

**Build:** Install the APK from the latest successful `feat/android-file-explorer` run in [GitHub Actions](https://github.com/Aki-Gra/MeowMeowFileManager/actions). Verify the SHA matches the PR head. Do not use an older APK.

**Safety:** Never test delete on personal files. The app can permanently delete files and has no recycle bin. Do not share private filenames or paths in reports.

## Required checks (record Pass / Fail / N/A)

- [ ] **Upgrade / launch:** The APK installs and opens without crashes, preserving selected-folder access where Android permits it. If Android rejects an update because the CI debug signing key differs, document the installer error; uninstalling deletes local app data and logs.
- [ ] **Navigation and permission:** Files, Storage, Cleanup and Settings tabs are reachable on the test phone. Browse shared storage/Downloads with full access; revoke permission and verify the SAF fallback works. Protected Android folders remain restricted.
- [ ] **File operations:** Navigate quickly between nested directories and back. Refresh, sort, create and rename test folders/files. **Try creating a folder with an existing name and renaming a file to another existing filename:** the app must refuse replacement. Renaming to the unchanged name must not fail. Confirm deletion works only for chosen disposable files.
- [ ] **Storage / Cleanup regression:** Storage figures are plausible beside Android Settings (note exact numbers if still different). Scan Downloads for a disposable 50 MiB+ file, check selection and cancel confirmation. Only explicitly confirmed files can be removed.
- [ ] **Cancellation regression:** Force-stop and relaunch, then navigate quickly and switch tabs. Neither a `StandaloneCoroutine was cancelled` snackbar nor a false cancellation entry in Settings should appear.
- [ ] **Local error log:** In Settings, enable error logging; add a Test entry and check timestamp. Force-stop/relaunch; the entry persists. Disable logging (test entry button disabled), then re-enable it.
- [ ] **Copy / Share:** Copy and inspect the error summary; open Share and dismiss. No private paths, filenames, stack traces or file contents should appear, and nothing uploads by itself.
- [ ] **Export .txt:** Tap **Export logs to .txt**, choose Downloads in Android's Save dialog, save, then open and read the UTF-8 file. Verify its timestamp, category and fixed error labels match Settings without private file information. Export does not clear history.
- [ ] **Cancel / empty states:** Cancel a second export (no new file or success message). Cancel Clear (history retained), then confirm Clear (history empty). Export is disabled for an empty log; history stays empty after relaunch.
- [ ] **Display:** Verify four bottom tabs, Settings controls and export button on a smaller screen in portrait and in dark mode.
- [ ] **Automation:** Current GitHub Actions `testDebugUnitTest` and `assembleDebug` are green and a fresh `meowmeow-debug-apk` artifact is published for the same commit.

**Report failures:** Describe which step failed, expected and actual result, app commit SHA, device Android version and sanitized screenshots. Never paste private file paths, filenames or contents.

## Previous testing and fixes

- **Initial real-device testing:** Files, Storage and Cleanup were reported as working. The code remains on PR #1 until final acceptance.
- **Navigation cancellation:** Previously surfaced `StandaloneCoroutine was cancelled`; normal coroutine cancellation is now propagated instead of surfaced as a user error. Final smoke recheck above remains.
- **Storage discrepancy:** Previously ~20 GB less total and ~6 GB less free than Android Settings. Changed the display to `StorageStatsManager` with a labelled filesystem fallback. The tester subsequently reported the updated app working; exact comparison figures were not recorded.
- **Diagnostics:** Logs are local, private, bounded to 100 entries, and omit raw error messages, paths and stack traces. Export is user-initiated through Android's document-save picker. Settings/export require the final test above.

## Diagnostic error-code verification

- [ ] With the new build, check **Settings → Error logging**: new errors and test entries have a stable `MM-...` code and action label, both on screen and in Copy/Export `.txt`.
- [ ] Reopen the app and verify older saved entries stay visible, with code `MM-OLD-xx` and **Unspecified (older entry)** rather than an invented operation.
- [ ] Using *disposable* files only, try renaming an item to an existing name (direct storage and SAF). The UI must explain the conflict without replacing an item or adding a new error-log entry. Repeat with an invalid name; neither validation result should be logged. An actual unexpected rename failure should still create a coded log entry.
- [ ] Confirm no private filenames, paths, exception messages, file contents or stack traces are recorded.

## Expected operation results (logging policy)

- [ ] Clear the local error log, then deliberately create a duplicate-named folder and rename a test file to an existing filename. Both should show friendly validation feedback; **Settings error count must remain unchanged**.
- [ ] Try an invalid name (for example a blank name) and confirm no log entry is produced.
- [ ] Confirm genuine I/O or permission failures are still logged with an operation code; never deliberately corrupt personal files to test failures.

## Launcher icon QA

- [ ] Install the latest APK and confirm the home-screen icon shows the MeowMeow cat and yellow folder instead of the default Android icon.
- [ ] Confirm the icon looks centered on launchers using circle and squircle masks, including the app drawer and Android Settings → Apps.
- [ ] On Android 13+, turn on themed icons and verify MeowMeow has a legible monochrome icon; disable themed icons to see the colored version.
