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
