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

## 7 — In-app image viewer (new)

- [ ] Open disposable **JPEG/JPG, PNG, WebP, BMP and GIF** images from Files and from a search/analysis result. MeowMeow shows the image without opening a browser or another app. GIF is a *still first-frame preview*, not animated.
- [ ] For Android versions supporting them, try **HEIC/HEIF** and **AVIF** and confirm supported images display. On versions without a decoder, confirm a safe failure message and manual **Open with…** option.
- [ ] Fit-to-screen shows the full image without cropping, preserving transparency and correct aspect ratio. Rotate a phone: the viewer still works.
- [ ] Use **pinch to zoom**, **Zoom +/−**, **Reset/Fit**, and pan at zoom levels above 100%. Reset brings the image back to fit-to-screen.
- [ ] View a portrait phone photo with EXIF rotation; it should appear correctly oriented. Check a very large image; preview is downsampled rather than decoding full-resolution pixels into memory.
- [ ] Open a corrupted image: an in-app explanation and Retry/Open with actions appear. Nothing external launches automatically.
- [ ] Choose **Open with…** for an image, select the phone's image app, and verify temporary read access is granted. Dismiss the chooser without changing the file.
- [ ] Open images from both full-storage access and Android's SAF folder picker. Confirm the original file is unchanged and no filenames/paths appear in error logs.
- [ ] Recheck PDF, TXT, JSON, DOCX, legacy DOC and ODT readers to ensure their default behaviour is unchanged.

## 8 — Share from viewer and file menus

- [ ] Open a PDF from Files. Tap the **Share** toolbar icon. Android's native Sharesheet appears and offers compatible destinations. No file is transmitted until a destination is selected.
- [ ] Cancel the Sharesheet. You should return to the same MeowMeow viewer without changing or saving the file. **Open with…** must still work independently.
- [ ] Repeat for PNG/JPEG, TXT, JSON, DOCX, DOC, GIF and an unsupported ZIP file. Verify the original complete file is received, not a picture of the preview, cropped bitmap or text copy.
- [ ] In Files **List**, tap a regular file's **three-dot menu → Share**. The Sharesheet opens directly without first loading the reader.
- [ ] Repeat in **Large icons** and **Thumbnails**. The three-dot menu still offers Copy, Rename and Delete according to existing permissions and confirmations.
- [ ] Folder three-dot menus **do not** offer Share (sharing a folder archive is out of scope). Read-only files **do** offer Share.
- [ ] Share through both Android full-access device storage and SAF-selected folders, including a scoped document whose provider reports a generic `application/octet-stream` MIME type. The target app should get correct MIME and temporary **read**, not write, permission.
- [ ] With access revoked, a moved/deleted file or no compatible sharing app, handle errors with a friendly message and without a crash. An expected Sharesheet cancel should not be logged as an error.
- [ ] Check diagnostics after a genuine sharing failure: only generic `MM-SHARE-FILE` code appears, no private filename, path, contents or exception message.
- [ ] In airplane mode, opening the Sharesheet must work. MeowMeow itself must not upload files to any server; only the user's selected destination may transfer files.
- [ ] Test a small-screen phone, long filenames and dark mode. The Share toolbar button and **Open with…** must remain accessible.
