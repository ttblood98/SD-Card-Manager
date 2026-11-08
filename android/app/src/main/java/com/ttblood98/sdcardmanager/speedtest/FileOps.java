package com.ttblood98.sdcardmanager.speedtest;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/** Everything that touches files: scanning the card, browsing destination folders, verified copying. */
final class FileOps {
    private FileOps() { }

    interface Log { void log(String s); }

    interface Progress { void onBytes(long n); }

    // ------------------------------------------------------------------ scanning

    static List<Item> scan(ContentResolver cr, Uri tree) throws IOException {
        List<Item> found = new ArrayList<>();
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        Deque<String> stack = new ArrayDeque<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String dirId = stack.pop();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
            try (Cursor c = cr.query(children, proj, null, null, null)) {
                if (c == null) {
                    if (dirId.equals(rootId)) throw new IOException("the card folder is not available");
                    continue;
                }
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    if (name == null || name.startsWith(".")) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                        stack.push(c.getString(0));
                    } else {
                        Item e = new Item();
                        e.name = name;
                        e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                        e.size = c.isNull(3) ? 0 : c.getLong(3);
                        e.mtime = c.isNull(4) ? 0 : c.getLong(4);
                        e.type = Item.typeOf(name);
                        found.add(e);
                    }
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ destination folders

    /** Sub-folders of a folder: each entry is {documentId, name}, sorted by name. */
    static List<String[]> listFolders(ContentResolver cr, Uri tree, String docId) {
        List<String[]> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = cr.query(children, proj, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) out.add(new String[]{c.getString(0), name});
            }
        }
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    /** Creates a folder (or returns the existing one with that name). Returns its document id. */
    static String createFolder(ContentResolver cr, Uri tree, String parentDocId, String name) throws IOException {
        for (String[] f : listFolders(cr, tree, parentDocId)) if (f[1].equals(name)) return f[0];
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId);
        Uri made;
        try {
            made = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            throw new IOException("could not create folder " + name + ": " + e.getMessage());
        }
        if (made == null) throw new IOException("could not create folder " + name);
        return DocumentsContract.getDocumentId(made);
    }

    static final class DestDir {
        Uri tree;
        String docId;
        Uri docUri;
        final Set<String> names = new HashSet<>();
    }

    static DestDir openDestDir(ContentResolver cr, Uri tree, String docId) {
        DestDir d = new DestDir();
        d.tree = tree;
        d.docId = docId;
        d.docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String n = c.getString(0);
                if (n != null) d.names.add(n.toLowerCase(Locale.ROOT));
            }
        }
        return d;
    }

    static DestDir subDir(ContentResolver cr, DestDir parent, String name) throws IOException {
        return openDestDir(cr, parent.tree, createFolder(cr, parent.tree, parent.docId, name));
    }

    // ------------------------------------------------------------------ copying

    static String mimeFor(String name) {
        int i = name.lastIndexOf('.');
        if (i > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    static long queryLong(ContentResolver cr, Uri uri, String col) {
        try (Cursor c = cr.query(uri, new String[]{col}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        }
        return -1;
    }

    static String queryString(ContentResolver cr, Uri uri, String col) {
        try (Cursor c = cr.query(uri, new String[]{col}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    /**
     * Copies one file, reads the copy back and compares size + CRC32. A difference is accepted only when it is Android's
     * GPS read-back redaction (see {@link #redactionTolerance}). On any problem the partial copy is deleted and this throws.
     */
    static Uri copyAndVerify(ContentResolver cr, Item e, DestDir d, byte[] buf, AtomicBoolean cancel, Progress progress,
                             Log log) throws Exception {
        Uri dest = null;
        try {
            dest = DocumentsContract.createDocument(cr, d.docUri, mimeFor(e.name), e.name);
            if (dest == null) throw new IOException("could not create the destination file");
            CRC32 crc = new CRC32();
            long n = 0;
            try (InputStream in = cr.openInputStream(e.uri); OutputStream out = cr.openOutputStream(dest, "w")) {
                if (in == null || out == null) throw new IOException("could not open streams");
                int r;
                while ((r = in.read(buf)) > 0) {
                    crc.update(buf, 0, r);
                    out.write(buf, 0, r);
                    n += r;
                    if (progress != null) progress.onBytes(r);
                    if (cancel.get()) throw new IOException("cancelled");
                }
                out.flush();
            }
            if (e.size > 0 && n != e.size) throw new IOException("read " + n + " bytes but the card lists " + e.size);
            long destSize = queryLong(cr, dest, DocumentsContract.Document.COLUMN_SIZE);
            if (destSize != n) throw new IOException("copy is " + destSize + " bytes, expected " + n);
            CRC32 crc2 = new CRC32();
            long m = 0;
            try (InputStream in2 = cr.openInputStream(dest)) {
                if (in2 == null) throw new IOException("could not read the copy back");
                int r;
                while ((r = in2.read(buf)) > 0) { crc2.update(buf, 0, r); m += r; }
            }
            if (m != n || crc2.getValue() != crc.getValue()) {
                String tol = (m == n) ? redactionTolerance(cr, e.uri, dest, buf) : null;
                if (tol == null) {
                    throw new IOException("verification failed: copy differs from original (wrote " + n + " B crc "
                            + Long.toHexString(crc.getValue()) + ", read back " + m + " B crc " + Long.toHexString(crc2.getValue())
                            + "). " + diagnose(cr, e.uri, dest, buf, crc.getValue()));
                }
                log.log("  note (" + e.name + "): " + tol);
            }
            String finalName = queryString(cr, dest, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (finalName != null && !finalName.equals(e.name)) log.log("  note: saved as '" + finalName + "' (renamed by the system)");
            return dest;
        } catch (Exception ex) {
            if (dest != null) {
                try { DocumentsContract.deleteDocument(cr, dest); } catch (Exception ignored) { }
            }
            throw ex;
        }
    }

    static boolean delete(ContentResolver cr, Uri uri) {
        try {
            return DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ verification helpers

    /** EXIF location tags that Android blanks when an app reads a photo back (privacy redaction). */
    private static final String[] GPS_TAGS = {
            android.media.ExifInterface.TAG_GPS_ALTITUDE, android.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
            android.media.ExifInterface.TAG_GPS_AREA_INFORMATION, android.media.ExifInterface.TAG_GPS_DOP,
            android.media.ExifInterface.TAG_GPS_DATESTAMP, android.media.ExifInterface.TAG_GPS_DEST_BEARING,
            android.media.ExifInterface.TAG_GPS_DEST_BEARING_REF, android.media.ExifInterface.TAG_GPS_DEST_DISTANCE,
            android.media.ExifInterface.TAG_GPS_DEST_DISTANCE_REF, android.media.ExifInterface.TAG_GPS_DEST_LATITUDE,
            android.media.ExifInterface.TAG_GPS_DEST_LATITUDE_REF, android.media.ExifInterface.TAG_GPS_DEST_LONGITUDE,
            android.media.ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, android.media.ExifInterface.TAG_GPS_DIFFERENTIAL,
            "GPSHPositioningError", android.media.ExifInterface.TAG_GPS_IMG_DIRECTION,
            android.media.ExifInterface.TAG_GPS_IMG_DIRECTION_REF, android.media.ExifInterface.TAG_GPS_LATITUDE,
            android.media.ExifInterface.TAG_GPS_LATITUDE_REF, android.media.ExifInterface.TAG_GPS_LONGITUDE,
            android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, android.media.ExifInterface.TAG_GPS_MAP_DATUM,
            android.media.ExifInterface.TAG_GPS_MEASURE_MODE, android.media.ExifInterface.TAG_GPS_PROCESSING_METHOD,
            android.media.ExifInterface.TAG_GPS_SATELLITES, android.media.ExifInterface.TAG_GPS_SPEED,
            android.media.ExifInterface.TAG_GPS_SPEED_REF, android.media.ExifInterface.TAG_GPS_STATUS,
            android.media.ExifInterface.TAG_GPS_TIMESTAMP, android.media.ExifInterface.TAG_GPS_TRACK,
            android.media.ExifInterface.TAG_GPS_TRACK_REF, android.media.ExifInterface.TAG_GPS_VERSION_ID,
    };

    /**
     * Accepts the copy ONLY if every differing byte lies inside a GPS tag of the original and reads back as zero
     * (Android's location redaction). Returns a note if accepted, null if not.
     */
    static String redactionTolerance(ContentResolver cr, Uri src, Uri dest, byte[] buf) {
        try {
            List<long[]> ranges = new ArrayList<>();
            try (InputStream in = cr.openInputStream(src)) {
                if (in == null) return null;
                android.media.ExifInterface ei = new android.media.ExifInterface(in);
                for (String tag : GPS_TAGS) {
                    long[] r = ei.getAttributeRange(tag);
                    if (r != null && r[1] > 0) ranges.add(r);
                }
            }
            if (ranges.isEmpty()) return null;
            byte[] bb = new byte[buf.length];
            long off = 0, diffs = 0;
            try (InputStream a = cr.openInputStream(src); InputStream b = cr.openInputStream(dest)) {
                if (a == null || b == null) return null;
                while (true) {
                    int ra = readFully(a, buf), rb = readFully(b, bb);
                    if (ra != rb) return null;
                    for (int j = 0; j < ra; j++) {
                        if (buf[j] == bb[j]) continue;
                        long pos = off + j;
                        boolean inside = false;
                        for (long[] r : ranges) if (pos >= r[0] && pos < r[0] + r[1]) { inside = true; break; }
                        if (!inside || bb[j] != 0) return null;
                        diffs++;
                    }
                    off += ra;
                    if (ra < buf.length) break;
                }
            }
            return diffs + " bytes in the GPS location tags read back as zero (Android hides location in read-back); "
                    + "everything else matched exactly.";
        } catch (Throwable t) {
            return null;
        }
    }

    static int readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int r = in.read(b, got, b.length - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    private static String hex(byte[] b, int from, int to, int mark) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            String h = String.format(Locale.US, "%02x", b[i] & 0xff);
            sb.append(i == mark ? "[" + h + "]" : h).append(' ');
        }
        return sb.toString().trim();
    }

    /** Runs only after a failed check: re-reads the original and the copy and says where and how they differ. */
    static String diagnose(ContentResolver cr, Uri src, Uri dest, byte[] buf, long firstCrc) {
        try (InputStream a = cr.openInputStream(src); InputStream b = cr.openInputStream(dest)) {
            byte[] bb = new byte[buf.length];
            CRC32 crcA = new CRC32();
            long off = 0, diffs = 0, first = -1, last = -1, lenA = 0, lenB = 0;
            String hexA = "", hexB = "";
            while (true) {
                int ra = readFully(a, buf), rb = readFully(b, bb);
                crcA.update(buf, 0, ra);
                lenA += ra;
                lenB += rb;
                int mn = Math.min(ra, rb);
                for (int j = 0; j < mn; j++) {
                    if (buf[j] != bb[j]) {
                        diffs++;
                        if (first < 0) {
                            first = off + j;
                            int from = Math.max(0, j - 12), to = Math.min(mn, j + 14);
                            hexA = hex(buf, from, to, j);
                            hexB = hex(bb, from, to, j);
                        }
                        last = off + j;
                    }
                }
                off += mn;
                if (ra < buf.length || rb < buf.length) break;
            }
            return "Diagnosis: second read of original = " + lenA + " B, crc " + Long.toHexString(crcA.getValue())
                    + (crcA.getValue() == firstCrc ? " (same as first read)" : " (DIFFERENT from first read)")
                    + "; copy = " + lenB + " B; differing bytes = " + diffs
                    + (diffs > 0 ? ", first at offset " + first + ", last at offset " + last
                    + "; original " + hexA + " | copy " + hexB : "");
        } catch (Exception ex) {
            return "Diagnosis failed: " + ex;
        }
    }
}
