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


## 9 — Open files in MeowMeow from OTHER Android apps

- [ ] Install the latest APK built from this PR's HEAD. Use **disposable** PDF, image, Word, ODT and text files.
- [ ] From another file manager / Downloads, tap a **PDF** → **Open with**. Confirm MeowMeow appears as one of the compatible apps. Select it and confirm the PDF renders with page navigation and zoom.
- [ ] Repeat with **JPG/JPEG, PNG, GIF, WebP** and, on supported devices, HEIC/HEIF/AVIF; the existing image viewer and pinch zoom remain available.
- [ ] Repeat with **TXT, JSON, XML, CSV, Markdown, DOC, DOCX and ODT**. Correct preview type should be selected even if the shared provider's filename has no extension (when it declares a supported MIME type).
- [ ] Repeat with text-source files such as HTML, YAML, CSS, JavaScript, Python and Kotlin where the source app declares a text MIME type.
- [ ] **Back** from an externally opened file should return to the source app's file list or message, not open MeowMeow's Files tab or launch an extra copy of the source app.
- [ ] In the externally opened viewer, **Share** sends the original file, not a screenshot. **Open with…** shows other apps without putting MeowMeow itself back into a self-opening loop.
- [ ] Cancel an external Open with chooser before selecting MeowMeow. Nothing changes or auto-opens.
- [ ] Test with MeowMeow's **All files access disabled**. An external app's grantable `content://` URI should still open in MeowMeow. The external flow must not request folder permissions or broad storage access.
- [ ] Try a revoked/stale URI or corrupted file. Show a generic message or the reader's normal failure UI, without a crash, automatic external handoff, or exposing private paths in error logs.
- [ ] Test an unsupported ZIP/EXE from another app: MeowMeow should NOT be a general-purpose handler advertised for binary/unknown files.
- [ ] Test both a normal `content://` document provider and a third-party `FileProvider` where possible. Provider permissions must be temporary read-only.
- [ ] Recheck normal internal file-opening in Files, Search and Analysis and confirm no regression.

**Android behavior:** App matching is controlled by Android and the sender's MIME type. We register supported, specific MIME types plus `text/*` over `content://`. A provider using only `application/octet-stream` (generic binary) may not list MeowMeow; registering for every binary file would misrepresent its reading capabilities. An HTTP URL is not a grantable local document and is not handled by this feature. The build does **not** silently claim to become the default app.
