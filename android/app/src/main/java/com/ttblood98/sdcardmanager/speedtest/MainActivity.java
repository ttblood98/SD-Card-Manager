package com.ttblood98.sdcardmanager.speedtest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Throwaway speed test: lists an SD card folder the way the system Files app does
 * (one content-provider query per folder returning name, size and date together)
 * and reports how long it takes, so it can be compared with the web page.
 */
public class MainActivity extends Activity {
    private static final int REQ_TREE = 1;

    private static class Entry {
        String name;
        Uri uri;
        long size;
        long mtime;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final List<Entry> files = new ArrayList<>();
    private TextView out;
    private ScrollView scroll;
    private Button pickBtn;
    private Button readBtn;
    private Uri treeUri;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("SD card native speed test");
        title.setTextSize(20);
        root.addView(title);

        pickBtn = new Button(this);
        pickBtn.setText("1. Pick SD card folder (DCIM) and time the listing");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });
        root.addView(pickBtn);

        readBtn = new Button(this);
        readBtn.setText("2. Read speed test (reads the 3 newest RAW files)");
        readBtn.setEnabled(false);
        readBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { readTest(); }
        });
        root.addView(readBtn);

        Button copyBtn = new Button(this);
        copyBtn.setText("3. Copy report to clipboard");
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyReport(); }
        });
        root.addView(copyBtn);

        scroll = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        log("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        log("Tap button 1 and choose the DCIM folder on the SD card.");
    }

    private void pickFolder() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null || data.getData() == null) {
            log("No folder chosen.");
            return;
        }
        treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            log("(could not persist permission: " + e + ")");
        }
        log("Picked: " + treeUri);
        startWork(new Runnable() { @Override public void run() { scan(); } });
    }

    private void startWork(final Runnable r) {
        busy = true;
        setButtons();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    log("FAILED: " + t);
                }
                busy = false;
                ui.post(new Runnable() { @Override public void run() { setButtons(); } });
            }
        }).start();
    }

    private void setButtons() {
        pickBtn.setEnabled(!busy);
        readBtn.setEnabled(!busy && !files.isEmpty());
    }

    private void scan() {
        files.clear();
        final ContentResolver cr = getContentResolver();
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        Deque<String> stack = new ArrayDeque<>();
        stack.push(DocumentsContract.getTreeDocumentId(treeUri));
        int dirs = 0;
        long t0 = SystemClock.elapsedRealtime();
        while (!stack.isEmpty()) {
            String dirId = stack.pop();
            dirs++;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId);
            long q0 = SystemClock.elapsedRealtime();
            int n = 0;
            Cursor c = cr.query(children, proj, null, null, null);
            if (c == null) { log("  folder " + dirId + ": null cursor"); continue; }
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (name == null || name.startsWith(".")) continue;
                    n++;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(id);
                    } else {
                        Entry e = new Entry();
                        e.name = name;
                        e.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        e.size = c.isNull(3) ? 0 : c.getLong(3);
                        e.mtime = c.isNull(4) ? 0 : c.getLong(4);
                        files.add(e);
                    }
                }
            } finally {
                c.close();
            }
            log("  folder " + dirId + ": " + n + " entries in " + (SystemClock.elapsedRealtime() - q0) + " ms");
        }
        long total = SystemClock.elapsedRealtime() - t0;

        Map<String, Integer> byExt = new TreeMap<>();
        long oldest = Long.MAX_VALUE, newest = 0, bytes = 0;
        for (Entry e : files) {
            int i = e.name.lastIndexOf('.');
            String x = i > 0 ? e.name.substring(i + 1).toLowerCase(Locale.ROOT) : "(none)";
            Integer cur = byExt.get(x);
            byExt.put(x, cur == null ? 1 : cur + 1);
            if (e.mtime > 0) { oldest = Math.min(oldest, e.mtime); newest = Math.max(newest, e.mtime); }
            bytes += e.size;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        log("RESULT: listed " + files.size() + " files in " + dirs + " folders, with sizes and dates, in "
                + total + " ms (" + String.format(Locale.US, "%.2f", total / 1000.0) + " s)");
        log("By type: " + byExt);
        log("Total size: " + String.format(Locale.US, "%.1f", bytes / 1073741824.0) + " GB");
        if (newest > 0) log("Dates: " + f.format(new Date(oldest)) + " .. " + f.format(new Date(newest)));
        log("Web page for comparison: 76 s to list + 62 s for dates (580 files).");
    }

    private void readTest() {
        if (busy || files.isEmpty()) return;
        startWork(new Runnable() { @Override public void run() { doRead(); } });
    }

    private void doRead() {
        List<Entry> raws = new ArrayList<>();
        for (Entry e : files) {
            String n = e.name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".nef") || n.endsWith(".nrw") || n.endsWith(".dng") || n.endsWith(".cr3")
                    || n.endsWith(".arw") || n.endsWith(".raf")) raws.add(e);
        }
        if (raws.isEmpty()) raws.addAll(files);
        Collections.sort(raws, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.mtime, a.mtime); }
        });
        int count = Math.min(3, raws.size());
        log("Read test: reading " + count + " newest files fully (nothing is saved)...");
        byte[] buf = new byte[1 << 20];
        long totalBytes = 0, totalMs = 0;
        for (int i = 0; i < count; i++) {
            Entry e = raws.get(i);
            long t0 = SystemClock.elapsedRealtime();
            long got = 0;
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(e.uri);
                long tOpen = SystemClock.elapsedRealtime() - t0;
                int r;
                while (in != null && (r = in.read(buf)) > 0) got += r;
                long ms = SystemClock.elapsedRealtime() - t0;
                totalBytes += got;
                totalMs += ms;
                log(String.format(Locale.US, "  %s: %.1f MB, open %d ms, total %d ms (%.1f MB/s)",
                        e.name, got / 1048576.0, tOpen, ms, ms > 0 ? (got / 1048576.0) / (ms / 1000.0) : 0));
            } catch (Exception ex) {
                log("  " + e.name + ": FAILED " + ex);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
            }
        }
        if (totalMs > 0) {
            log(String.format(Locale.US, "READ RESULT: %.1f MB in %d ms = %.1f MB/s average",
                    totalBytes / 1048576.0, totalMs, (totalBytes / 1048576.0) / (totalMs / 1000.0)));
        }
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String text;
        synchronized (report) { text = report.toString(); }
        cm.setPrimaryClip(ClipData.newPlainText("report", text));
        log("Report copied to clipboard. Paste it into the chat.");
    }

    private void log(final String msg) {
        final String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n";
        synchronized (report) { report.append(line); }
        ui.post(new Runnable() {
            @Override public void run() {
                out.append(line);
                scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
            }
        });
    }
}
