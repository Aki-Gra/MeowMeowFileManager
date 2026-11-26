# Experiment: Explorer list, large icons and thumbnail grids

Branch: \`experiment/explorer-icons-thumbnails\`
Stacked on: \`feat/advanced-in-app-document-reader\` / PR #5

**Purpose:** trial visual file browsing without changing the existing list,
storage logic, or file-operation rules. The experiment is not ready to merge
into PR #5 until physical-device testing passes.

## Preparation

- Install the build artifact for the latest experiment commit.
- Use disposable test files in Downloads and a folder selected using Android SAF.
- Create a folder with 100–300 mixed files (JPEG, PNG, GIF, PDFs, TXT/DOCX,
  folders, unsupported extensions) including a 15+ MP camera photo, an
  EXIF-rotated image, and a corrupted image. Never delete personal files.

## Layout / usability checks

- [ ] **List** is the default on a fresh install. The existing file rows and
  actions behave exactly as before.
- [ ] In Files, tap **Choose file view** beside the sort control. Select
  **Large icons**: each file/folder appears as a large, labelled icon in a grid.
- [ ] Change to **Thumbnails**: supported images display actual image
  previews. Folders and non-image files display large file-type icons.
- [ ] Confirm image names and sizes remain visible, including long filenames.
- [ ] Sorting by name/modified/size preserves the same item order in every
  view, and folders stay first.
- [ ] Change view in one folder, navigate to other folders, switch tabs,
  then force-stop/relaunch: the selected view preference persists.
- [ ] Test portrait/landscape, narrow phone and dark mode; controls, create
  folder button, navigation and the bottom task panel must not overlap.
- [ ] A folder containing hundreds of files scrolls smoothly and loads
  previews only as they come into view. No UI freeze or memory crash.
- [ ] Original images remain unmodified. No network, external services or
  persistent image/filename index/cache is created.

## File operations in both grid views

- [ ] Tap a folder to navigate into it; tap a PDF/text/image to open the
  existing in-app reader, then return to the same folder.
- [ ] Tap the 3-dot icon on a tile: Copy, Rename and Delete are available
  under the same permission rules as List. Delete still requires confirmation.
- [ ] Enter multi-select: tap tiles and checkboxes, Select all/Deselect all,
  then copy/move/delete test files using the existing bulk flows.
- [ ] Test copy/paste destination browsing with large icons and thumbnails.
  No file is overwritten automatically.
- [ ] Search results keep their readable list with location information;
  returning to Files restores the selected grid view.
- [ ] With a background search/duplicate scan running, switching the
  Explorer layout must not unexpectedly cancel or corrupt those tasks.

## Safety / fallback

- [ ] Test with SAF-only access and with approved full-storage access.
  Thumbnails should load from both URI types; revoked permission must show
  fallback icons instead of a crash.
- [ ] Corrupt/unsupported images show a generic image icon in the grid,
  but still open in the in-app reader with its existing error message.
- [ ] GIF preview is a still frame. Very large files above the thumbnail
  size limit use normal icons.
- [ ] Confirm no private filenames, paths or file contents are recorded
  in diagnostic logs during a thumbnail failure.

**Report:** Pass/Fail, Android version, build commit, view mode, steps to
reproduce and sanitized screenshots. Do not include private file information.
