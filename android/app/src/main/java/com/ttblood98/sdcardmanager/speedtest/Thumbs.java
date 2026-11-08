package com.ttblood98.sdcardmanager.speedtest;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.net.Uri;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thumbnails for the grid (background loading + memory cache) and larger previews for the viewer.
 * JPEG/PNG use the system thumbnail; RAW (TIFF-based, e.g. NEF) uses the JPEG preview stored inside the file.
 */
final class Thumbs {
    interface Listener { void onThumbsChanged(); }

    private final ContentResolver cr;
    private final Handler ui;
    private final FileOps.Log log;
    private final Listener listener;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ExecutorService viewerPool = Executors.newSingleThreadExecutor();
    private final Set<String> pending = new HashSet<>();  // UI thread only
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private boolean refreshScheduled = false;
    volatile boolean paused = false;

    private final AtomicInteger rawOk = new AtomicInteger(), rawFail = new AtomicInteger(), picOk = new AtomicInteger(),
            picFail = new AtomicInteger();
    private final AtomicLong rawMs = new AtomicLong(), picMs = new AtomicLong();
    private final AtomicBoolean rawOkLogged = new AtomicBoolean(), rawFailLogged = new AtomicBoolean(),
            picFailLogged = new AtomicBoolean();

    Thumbs(ContentResolver cr, Handler ui, FileOps.Log log, Listener listener) {
        this.cr = cr;
        this.ui = ui;
        this.log = log;
        this.listener = listener;
    }

    void shutdown() {
        pool.shutdownNow();
        viewerPool.shutdownNow();
    }

    void forgetFailures() { missing.clear(); }

    Bitmap cached(Item e) { return cache.get(e.key()); }

    /** Shows the cached thumbnail, or a blank and queues a background load. UI thread only. */
    void bind(ImageView iv, Item e) {
        final String key = e.key();
        Bitmap b = cache.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        if (e.type == Item.T_OTHER || missing.contains(key)) return;
        seen.put(key, SystemClock.elapsedRealtime());
        if (!pending.add(key)) return;
        final Item fe = e;
        try {
            pool.execute(() -> {
                Long s = seen.get(key);
                boolean wanted = s != null && SystemClock.elapsedRealtime() - s < 2500;
                if (!wanted || paused) { ui.post(() -> pending.remove(key)); return; }
                final Bitmap bm = loadThumb(fe);
                ui.post(() -> {
                    pending.remove(key);
                    if (bm != null) cache.put(key, bm); else missing.add(key);
                    if (!refreshScheduled) {
                        refreshScheduled = true;
                        ui.postDelayed(() -> { refreshScheduled = false; listener.onThumbsChanged(); }, 80);
                    }
                });
            });
        } catch (Exception ex) {
            pending.remove(key);
        }
    }

    /** Loads a large preview (longest side about maxDim) on a background thread and delivers it on the UI thread. */
    void loadLarge(Item e, int maxDim, java.util.function.Consumer<Bitmap> done) {
        try {
            viewerPool.execute(() -> {
                Bitmap b = null;
                try {
                    if (e.type == Item.T_RAW) b = rawPreview(cr, e.uri, maxDim, true);
                    else if (e.type != Item.T_OTHER) b = decodeSampled(cr, e.uri, maxDim);
                } catch (Throwable t) {
                    log.log("Preview failed for " + e.name + ": " + t.getMessage());
                }
                final Bitmap fb = b;
                ui.post(() -> done.accept(fb));
            });
        } catch (Exception ignored) { }
    }

    String stats() {
        int ro = rawOk.get(), rf = rawFail.get(), po = picOk.get(), pf = picFail.get();
        return "RAW " + ro + " ok / " + rf + " failed" + (ro > 0 ? ", avg " + (rawMs.get() / ro) + " ms" : "")
                + "; JPEG/PNG " + po + " ok / " + pf + " failed" + (po > 0 ? ", avg " + (picMs.get() / po) + " ms" : "")
                + "; cached " + cache.size() / 1024 + " MB";
    }

    private Bitmap loadThumb(Item e) {
        long t0 = SystemClock.elapsedRealtime();
        Bitmap bm = null;
        String err = null;
        try {
            if (e.type == Item.T_RAW) {
                bm = rawPreview(cr, e.uri, 240, false);
            } else {
                try { bm = DocumentsContract.getDocumentThumbnail(cr, e.uri, new Point(256, 256), null); } catch (Exception ex) { err = ex.toString(); }
                if (bm == null) bm = decodeSampled(cr, e.uri, 256);
            }
        } catch (Throwable t) {
            err = t.getMessage() != null ? t.getMessage() : t.toString();
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        if (e.type == Item.T_RAW) {
            if (bm != null) {
                rawOk.incrementAndGet(); rawMs.addAndGet(ms);
                if (rawOkLogged.compareAndSet(false, true)) log.log("First RAW preview: " + e.name + " " + bm.getWidth() + "x" + bm.getHeight() + " in " + ms + " ms");
            } else {
                rawFail.incrementAndGet();
                if (rawFailLogged.compareAndSet(false, true)) log.log("RAW preview failed for " + e.name + ": " + err);
            }
        } else {
            if (bm != null) { picOk.incrementAndGet(); picMs.addAndGet(ms); }
            else {
                picFail.incrementAndGet();
                if (picFailLogged.compareAndSet(false, true)) log.log("Thumbnail failed for " + e.name + ": " + err);
            }
        }
        return bm;
    }

    static Bitmap decodeSampled(ContentResolver cr, Uri uri, int target) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int mx = Math.max(o.outWidth, o.outHeight);
        if (mx <= 0) return null;
        int sample = 1;
        while (mx / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = cr.openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
        if (b == null) return null;
        int orient = 1;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) orient = new android.media.ExifInterface(in).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, 1);
        } catch (Exception ignored) { }
        return rotate(b, orient);
    }

    // ---- RAW (TIFF-based, e.g. NEF): find the JPEG preview stored inside the file

    private static byte[] readAt(FileChannel ch, long pos, int len) throws IOException {
        if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("bad read length " + len);
        ByteBuffer bb = ByteBuffer.allocate(len);
        while (bb.hasRemaining()) {
            int r = ch.read(bb, pos + bb.position());
            if (r <= 0) break;
        }
        return bb.position() == len ? bb.array() : Arrays.copyOf(bb.array(), bb.position());
    }

    private static int u16(byte[] b, int o, ByteOrder bo) { return ByteBuffer.wrap(b).order(bo).getShort(o) & 0xffff; }

    private static long u32(byte[] b, int o, ByteOrder bo) { return ByteBuffer.wrap(b).order(bo).getInt(o) & 0xffffffffL; }

    static void walkIfd(FileChannel ch, long size, ByteOrder bo, long off, int depth, boolean ifd0,
                        List<long[]> cands, int[] orient, Set<Long> seenOffsets) throws IOException {
        if (off <= 0 || off + 2 > size || depth > 3 || !seenOffsets.add(off)) return;
        byte[] cb = readAt(ch, off, 2);
        if (cb.length < 2) return;
        int count = u16(cb, 0, bo);
        if (count == 0 || count > 600) return;
        byte[] buf = readAt(ch, off + 2, count * 12 + 4);
        if (buf.length < count * 12 + 4) return;
        long jpgOff = -1, jpgLen = -1, stripOff = -1, stripLen = -1, subVal = 0, subCnt = 0, stripOffCnt = 0, stripLenCnt = 0;
        int comp = -1;
        for (int i = 0; i < count; i++) {
            int o = i * 12;
            int tag = u16(buf, o, bo), type = u16(buf, o + 2, bo);
            long cnt = u32(buf, o + 4, bo);
            long val = (type == 3 && cnt == 1) ? u16(buf, o + 8, bo) : u32(buf, o + 8, bo);
            switch (tag) {
                case 0x0201: jpgOff = val; break;
                case 0x0202: jpgLen = val; break;
                case 0x0103: comp = (int) val; break;
                case 0x0111: stripOff = val; stripOffCnt = cnt; break;
                case 0x0117: stripLen = val; stripLenCnt = cnt; break;
                case 0x014a: subVal = val; subCnt = cnt; break;
                case 0x0112: if (ifd0 && depth == 0) orient[0] = (int) val; break;
                default: break;
            }
        }
        if (jpgOff > 0 && jpgLen > 0) cands.add(new long[]{jpgOff, jpgLen});
        if ((comp == 6 || comp == 7) && stripOff > 0 && stripLen > 0 && stripOffCnt == 1 && stripLenCnt == 1) {
            cands.add(new long[]{stripOff, stripLen});
        }
        if (subCnt == 1) {
            walkIfd(ch, size, bo, subVal, depth + 1, false, cands, orient, seenOffsets);
        } else if (subCnt > 1 && subCnt <= 8 && subVal + subCnt * 4 <= size) {
            byte[] sb = readAt(ch, subVal, (int) subCnt * 4);
            for (int i = 0; i + 4 <= sb.length; i += 4) {
                walkIfd(ch, size, bo, u32(sb, i, bo), depth + 1, false, cands, orient, seenOffsets);
            }
        }
        long next = u32(buf, count * 12, bo);
        if (depth == 0 && ifd0 && next > 0) walkIfd(ch, size, bo, next, 0, false, cands, orient, seenOffsets);
    }

    /**
     * Decodes the JPEG preview inside a RAW file. For thumbnails (largest=false) it uses the smallest preview that is
     * at least 400px; for the viewer (largest=true) it uses the biggest one. Output longest side is about target px.
     */
    static Bitmap rawPreview(ContentResolver cr, Uri uri, int target, boolean largest) throws IOException {
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("could not open the file");
            FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            long size = ch.size();
            byte[] head = readAt(ch, 0, 8);
            if (head.length < 8) throw new IOException("file too short");
            ByteOrder bo;
            if (head[0] == 0x49 && head[1] == 0x49) bo = ByteOrder.LITTLE_ENDIAN;
            else if (head[0] == 0x4d && head[1] == 0x4d) bo = ByteOrder.BIG_ENDIAN;
            else throw new IOException("not a TIFF-based RAW file");
            if (u16(head, 2, bo) != 42) throw new IOException("not a TIFF-based RAW file");
            List<long[]> cands = new ArrayList<>();
            int[] orient = {1};
            walkIfd(ch, size, bo, u32(head, 4, bo), 0, true, cands, orient, new HashSet<Long>());
            Collections.sort(cands, (a, b) -> Long.compare(a[1], b[1]));  // smallest first
            if (largest) Collections.reverse(cands);
            byte[] fallback = null;
            int fallbackMax = 0;
            Bitmap best = null;
            for (long[] c : cands) {
                if (c[1] < 500 || c[0] + c[1] > size) continue;
                byte[] jpg = readAt(ch, c[0], (int) c[1]);
                if (jpg.length < 2 || (jpg[0] & 0xff) != 0xff || (jpg[1] & 0xff) != 0xd8) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                int mx = Math.max(o.outWidth, o.outHeight);
                if (mx <= 0) continue;
                if (largest || mx >= 400) { best = decodeJpegSampled(jpg, mx, target); if (best != null) break; }
                else if (mx > fallbackMax) { fallback = jpg; fallbackMax = mx; }
            }
            if (best == null && fallback != null) best = decodeJpegSampled(fallback, fallbackMax, target);
            if (best == null) throw new IOException("no JPEG preview found inside the RAW file (" + cands.size() + " candidates)");
            return rotate(best, orient[0]);
        }
    }

    private static Bitmap decodeJpegSampled(byte[] jpg, int mx, int target) {
        int sample = 1;
        while (mx / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    static Bitmap rotate(Bitmap b, int orientation) {
        int deg = orientation == 3 ? 180 : orientation == 6 ? 90 : orientation == 8 ? 270 : 0;
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }
}
