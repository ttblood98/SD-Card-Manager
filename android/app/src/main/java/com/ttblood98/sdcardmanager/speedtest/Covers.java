package com.ttblood98.sdcardmanager.speedtest;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album covers for the destination picker: the newest photo in each folder, shown as a thumbnail. Covers and photo
 * counts are saved to disk so albums appear instantly next time, then refreshed in the background once per session
 * (or after something was copied into them).
 */
final class Covers {
    private final MainActivity a;
    private final File dir;
    private final SharedPreferences meta;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private final Set<String> refreshed = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    Covers(MainActivity a) {
        this.a = a;
        dir = new File(a.getCacheDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        meta = a.getSharedPreferences("album_covers", 0);
    }

    static String key(Uri tree, String docId) { return tree + "|" + docId; }

    /** Forget which albums were checked this session, so the next visit re-reads them (after a copy). */
    void invalidateAll() { refreshed.clear(); }

    void shutdown() { pool.shutdownNow(); }

    private File file(String key) {
        return new File(dir, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".jpg");
    }

    /** Fills a cover ImageView and its "N photos" label. UI thread only. */
    void bind(final ImageView iv, final TextView count, final Uri tree, final String docId) {
        final String key = key(tree, docId);
        iv.setTag(key);
        count.setTag(key);
        Bitmap b = mem.get(key);
        iv.setImageBitmap(b);
        String n = meta.getString(key + "#n", null);
        count.setText(n != null ? n : " ");
        final boolean haveMem = b != null;
        try {
            pool.execute(() -> {
                if (!haveMem) {
                    File f = file(key);
                    if (f.exists()) {
                        Bitmap disk = BitmapFactory.decodeFile(f.getPath());
                        if (disk != null) {
                            mem.put(key, disk);
                            a.h.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(disk); });
                        }
                    }
                }
                if (!refreshed.add(key)) return;
                refresh(key, tree, docId, iv, count);
            });
        } catch (Exception ignored) { }
    }

    private void refresh(String key, Uri tree, String docId, ImageView iv, TextView count) {
        FileOps.Children ch;
        try {
            ch = FileOps.listChildren(a.getContentResolver(), tree, docId);
        } catch (Exception e) {
            refreshed.remove(key);
            return;
        }
        Item cover = ch.photos.isEmpty() ? null : ch.photos.get(0);
        if (cover == null && !ch.folders.isEmpty()) {
            // no photos directly inside: borrow one from the first sub-folder
            try {
                FileOps.Children sub = FileOps.listChildren(a.getContentResolver(), tree, ch.folders.get(0)[0]);
                if (!sub.photos.isEmpty()) cover = sub.photos.get(0);
            } catch (Exception ignored) { }
        }
        final String label = describe(ch);
        String coverKey = cover == null ? "" : cover.key();
        String old = meta.getString(key + "#c", null);
        File f = file(key);
        Bitmap nb = null;
        boolean cleared = false;
        if (cover != null && (!coverKey.equals(old) || !f.exists())) {
            nb = a.thumbs.loadThumbSync(cover);
            if (nb != null) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    nb.compress(Bitmap.CompressFormat.JPEG, 85, out);
                } catch (Exception ignored) { }
                mem.put(key, nb);
            }
        } else if (cover == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            mem.remove(key);
            cleared = true;
        }
        meta.edit().putString(key + "#c", coverKey).putString(key + "#n", label).apply();
        final Bitmap fb = nb;
        final boolean fc = cleared;
        a.h.post(() -> {
            if (key.equals(count.getTag())) count.setText(label);
            if (key.equals(iv.getTag())) {
                if (fb != null) iv.setImageBitmap(fb);
                else if (fc) iv.setImageBitmap(null);
            }
        });
    }

    static String describe(FileOps.Children ch) {
        int p = ch.photos.size(), f = ch.folders.size();
        if (p == 0 && f == 0) return "Empty";
        StringBuilder sb = new StringBuilder();
        if (p > 0) sb.append(p).append(p == 1 ? " photo" : " photos");
        if (f > 0) sb.append(sb.length() > 0 ? " · " : "").append(f).append(f == 1 ? " album" : " albums");
        return sb.toString();
    }
}
