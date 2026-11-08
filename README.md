# SD-Card-Manager

Offload has two versions with the same features:

- **Windows (Chrome or Edge):** a progressive web app at the root of this repository (`index.html`, `js/`, `css/`).
  Hosted with GitHub Pages, it opens at `https://<user>.github.io/SD-Card-Manager/` and can be installed from the
  browser's address bar (Install app). It is built for mouse and keyboard.
- **Android:** a native app in `android/` (see below).

The original browser feasibility test is kept at `test/feasibility.html`.

## Windows app (web)

- **Setup:** choose the card's DCIM folder once; add a photo location (for example Pictures) once. Both are remembered.
- **Gallery:** grouped by day; RAW / JPEG / Other filters (Other is off by default), a date dropdown with a
  pick-the-first-and-last-day calendar, sort. Hold **Ctrl and use the scroll wheel** (or the - / + buttons) to change
  the thumbnail size; photos glide to their new places and the one under the mouse stays put.
- **Selecting:** click opens a photo; **click and drag across photos** selects a range (the grid scrolls at the edges);
  Shift+click, Ctrl+click, the round check on each photo, Ctrl+A, Esc; each day has a Select button.
- **Viewer:** the **scroll wheel zooms** around the pointer, drag pans when zoomed; drag left/right, the side arrows or
  the arrow keys move between photos; double-click zooms; Space selects; Del deletes. Details show resolution,
  camera, lens and exposure.
- **Copy / Move:** pick the album in Offload's album browser (cover photos, counts, recent albums, New album, a preview
  of what is already inside). Every copy is read back and checked (CRC32); Move deletes an original only after its
  copy verifies. Files already in the album are skipped, never overwritten. "Show in album" opens it afterwards.
- **Delete:** with a confirmation; deleting one photo offers to delete its matching RAW/JPEG too (ticked by default).
- Runs offline once loaded (service worker). Browsers cannot set a file's date, so copies get today's file date;
  the photo's own "date taken" inside the file is unchanged.
- `test/e2e/` has an end-to-end test that drives the app in headless Chromium against a fake card.

## Android app: Offload

The `android/` folder holds a native Android app. It replaced the web page on Android, where folder access is far
too slow (a native scan of 580 files takes about 0.1 s; the browser took about 2 minutes).

- **Gallery:** browse the card grouped by day, filter by type (RAW / JPEG / other) and date (dropdown), sort (top bar).
  Pinch to change the grid: spread fingers for fewer, bigger thumbnails; pinch for more, smaller ones.
  "Other" (non-photo) files are hidden until you turn that filter on. "Pick dates" opens a calendar where you tap
  the first and last day; dots mark days with photos.
- **Viewer:** swipe between photos, pinch or double-tap to zoom, see resolution, camera, lens and exposure.
- **Selecting:** tap a photo to view it full screen; long-press to start selecting, and keep your finger down and drag
  to select a range (the list scrolls at the edges). Each day has a Select button.
- **Copy / Move:** pick the destination album in the app's own album browser: album tiles with cached cover photos and
  counts, recent albums, a preview of what is already inside, New album. Every copy is read back and checked; Move
  deletes an original only after its copy verifies.
- **Delete:** delete selected files (or the photo in the viewer) from the card, after a confirmation.
- **Settings (gear icon):** card folder, destination folders (added once with Android's picker), date folders,
  grid size, activity log.

Builds come from GitHub Actions (`.github/workflows/android-apk.yml`); download the `SDImporter-apk` artifact from a
run (the app is called Offload). Builds are signed with the key in the repository secrets, so new versions install over old ones.
