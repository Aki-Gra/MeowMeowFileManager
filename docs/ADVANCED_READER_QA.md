# Advanced MeowMeow in-app reader — device QA

## Preparation

- Install the debug APK produced by the latest successful workflow for this PR's head commit.
- Use **disposable, non-sensitive** test files in a selected SAF folder and in Downloads with All files access.
- Prepare a 3-page PDF, TXT, JSON, DOCX, legacy DOC (Word 97–2003), ODT, Markdown, CSV and an unsupported ZIP file. Prepare an encrypted PDF and a deliberately corrupted PDF if available.
- Keep another PDF reader installed for testing the explicit external-app action.
- Record Android version, device class, build commit, and Pass/Fail per section.

## 1 — Default behaviour

- [ ] Tap a PDF, TXT, JSON, DOCX, legacy DOC, ODT, Markdown or CSV in Files. **MeowMeow's reader opens inside the app** immediately.
- [ ] No browser, system file chooser, Google Drive or external reader launches automatically.
- [ ] Back/Close returns to the same file list without changing file contents or navigation.
- [ ] Reader is available in both full-access shared storage and SAF-selected folders without granting additional permissions.

## 2 — PDF reader

- [ ] PDF page 1 renders (not a blank screen). Use Next/Previous to navigate all pages and check page numbers.
- [ ] Zoom +/− updates the page without a crash. Pan/scroll a zoomed page; the original stays unchanged.
- [ ] Repeat with landscape and a longer PDF; loading indicators and navigation behave reasonably.
- [ ] Encrypted/invalid PDF shows a helpful in-app failure, not an automatic external handoff.
- [ ] Confirm no huge bitmap/OOM crash when switching pages rapidly.

## 3 — Text and structured files

- [ ] TXT, JSON, XML, CSV, Markdown and LOG open read-only with original text, line breaks, accents/Unicode and indentation visible.
- [ ] Adjust A− / A+; text remains scrollable and selectable/copyable.
- [ ] Check UTF-8 BOM and UTF-16 BOM text files when available.
- [ ] Large text files are limited/truncated with a visible note or declined safely (never freeze or modify source).

## 4 — Office formats (text preview, not pixel-perfect layout)

- [ ] DOCX opens with paragraph text and line breaks; tables/text are readable in basic form.
- [ ] **Legacy .DOC** opens with readable extracted text (check on the physical device; runtime compatibility of POI matters).
- [ ] ODT opens with paragraph text; no macros, external links or embedded resources execute.
- [ ] It is acceptable that fonts, images, complex tables, shapes and original page layout are not reproduced. The UI indicates extracted text/read only.
- [ ] Invalid Word file is handled with an in-app explanation and no crash.

## 5 — External reader ONLY on explicit request

- [ ] Tap the top-bar **Open with…** on a PDF. Android displays an external app chooser; choose a reader and confirm it receives access to the selected file.
- [ ] Cancel the chooser and return to MeowMeow; no file change.
- [ ] Tap an unsupported ZIP. MeowMeow displays an in-app unsupported message; an external app is not invoked unless **Open with another app** is tapped.
- [ ] If no compatible external app is installed, MeowMeow displays a readable message instead of crashing.

## 6 — Privacy and regression

- [ ] Reader works in airplane mode; nothing requires internet.
- [ ] Check Settings → Error logging for generic reader codes if a genuine read/render error occurs. No private paths, filenames, file contents, exception messages or stack traces are captured.
- [ ] Rapidly open/close multiple documents; no “StandaloneCoroutine was cancelled” error appears.
- [ ] Confirm Files, Storage, Cleanup, Settings and file rename/delete behaviour still work.
- [ ] On a narrow screen and in dark mode, the reader's Back, title, Open with…, page buttons and text controls fit.

**Required before merge:** successful tests and debug APK build on this branch, plus physical-device sign-off. Do not merge to main until basic-features work has been checked for integration conflicts.
