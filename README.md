# SD-Card-Manager

`index.html` is a one-file feasibility test for importing photos from an SD card
in the browser (File System Access API): SD card access, Nikon NEF/JPEG
metadata, copy, delete, rename/move, and whether original file dates survive a copy.

## Running it
Open the GitHub Pages URL in **Chrome or Edge** (phone and PC):
`https://<user>.github.io/SD-Card-Manager/`

1. Pick the card's `DCIM` folder (or a subfolder like `100NIKON`).
2. Tap "Select newest NEF + JPG".
3. Pick a safe destination folder.
4. Run tests A-D in order (read metadata, copy, verify, delete copies), then E, F, G.
5. Tap "Copy full report" and paste the result back.

The test never touches your photos; it only deletes its own copies and one temp file it creates on the card.

## Android app (SD Importer)

The `android/` folder holds a native Android app. It replaced the web page on Android, where folder access is far
too slow (a native scan of 580 files takes about 0.1 s; the browser took about 2 minutes).

- **Gallery:** browse the card grouped by day, filter by type (RAW / JPEG / other) and date (dropdown), sort (top bar).
  Pinch to change the grid: spread fingers for more, smaller thumbnails; pinch for fewer, bigger ones.
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
run. Builds are signed with the key in the repository secrets, so new versions install over old ones.
