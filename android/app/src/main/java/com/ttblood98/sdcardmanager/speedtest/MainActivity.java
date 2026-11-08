package com.ttblood98.sdcardmanager.speedtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.graphics.Point;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * SD card importer, first working version.
 *  - lists the card the way the system Files app does (fast)
 *  - filters by file type and date, multi-select
 *  - Copy, or Move ("cut"): each file is copied, read back and checksummed, and the original is
 *    deleted only after that file verified. Any failure keeps the original.
 */
public class MainActivity extends Activity {
    private static final int REQ_SRC = 1, REQ_DST = 2;
    private static final String PREFS = "sdcard_prefs", K_SRC = "src_tree_v2", K_DST = "dst_tree_v2";
    private static final int T_RAW = 0, T_JPG = 1, T_PNG = 2, T_OTHER = 3;
    private static final String[] T_NAMES = {"RAW", "JPEG", "PNG", "Other"};
    private static final Set<String> RAW_EXT = new HashSet<>(java.util.Arrays.asList(
            "nef", "nrw", "dng", "cr2", "cr3", "crw", "arw", "raf", "orf", "rw2", "pef", "srw", "x3f", "raw"));

    private static class Entry {
        String name;
        Uri uri;
        long size;
        long mtime;
        int type;
        boolean selected;
    }

    private static class DestDir {
        String docId;
        Uri docUri;
        final Set<String> names = new HashSet<>();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final List<Entry> files = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private final boolean[] typeOn = {true, true, true, true};
    private long dateFrom = 0, dateTo = Long.MAX_VALUE;
    private String dateLabel = "All dates";

    private Uri srcTree, dstTree;
    private volatile boolean busy = false, cancel = false;

    private TextView status, selInfo, logView;
    private ScrollView logScroll;
    private ListView listView;
    private Button srcBtn, rescanBtn, dstBtn, dateBtn, selAllBtn, clearBtn, copyBtn, moveBtn, cancelBtn, thumbBtn, reportBtn;
    private CheckBox dateFolders;
    private final CheckBox[] typeBoxes = new CheckBox[4];
    private BaseAdapter adapter;
    private final SimpleDateFormat fmtDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    // ---------------------------------------------------------------- UI

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (8 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        LinearLayout r1 = row();
        srcBtn = button("Card folder", v -> pickTree(REQ_SRC));
        rescanBtn = button("Rescan", v -> startScan());
        dstBtn = button("Destination", v -> pickTree(REQ_DST));
        r1.addView(srcBtn, weight());
        r1.addView(rescanBtn, weight());
        r1.addView(dstBtn, weight());
        root.addView(r1);

        status = new TextView(this);
        status.setTextSize(12);
        root.addView(status);

        LinearLayout r2 = row();
        r2.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 4; i++) {
            final int t = i;
            CheckBox cb = new CheckBox(this);
            cb.setText(T_NAMES[i]);
            cb.setChecked(true);
            cb.setOnCheckedChangeListener((v, on) -> { typeOn[t] = on; applyFilter(); });
            typeBoxes[i] = cb;
            r2.addView(cb, weight());
        }
        root.addView(r2);

        LinearLayout r3 = row();
        dateBtn = button("Date: All dates", v -> chooseDate());
        selAllBtn = button("Select all", v -> { for (Entry e : shown) e.selected = true; refreshList(); });
        clearBtn = button("Clear", v -> { for (Entry e : files) e.selected = false; refreshList(); });
        r3.addView(dateBtn, weight());
        r3.addView(selAllBtn, weight());
        r3.addView(clearBtn, weight());
        root.addView(r3);

        selInfo = new TextView(this);
        selInfo.setTextSize(13);
        root.addView(selInfo);

        listView = new ListView(this);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout rowV;
                CheckBox cb;
                TextView name, meta;
                if (convert == null) {
                    rowV = new LinearLayout(MainActivity.this);
                    rowV.setOrientation(LinearLayout.HORIZONTAL);
                    rowV.setGravity(Gravity.CENTER_VERTICAL);
                    rowV.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
                    cb = new CheckBox(MainActivity.this);
                    cb.setFocusable(false);
                    cb.setClickable(false);
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    name = new TextView(MainActivity.this);
                    name.setTypeface(Typeface.DEFAULT_BOLD);
                    meta = new TextView(MainActivity.this);
                    meta.setTextSize(12);
                    col.addView(name);
                    col.addView(meta);
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams((int) (64 * dp), (int) (64 * dp));
                    ivp.setMargins(0, 0, (int) (8 * dp), 0);
                    rowV.addView(cb);
                    rowV.addView(iv, ivp);
                    rowV.addView(col);
                    rowV.setTag(new View[]{cb, name, meta, iv});
                } else {
                    rowV = (LinearLayout) convert;
                }
                View[] v = (View[]) rowV.getTag();
                Entry e = shown.get(i);
                ((CheckBox) v[0]).setChecked(e.selected);
                ((TextView) v[1]).setText(e.name);
                ((TextView) v[2]).setText(T_NAMES[e.type] + " · " + fmtSize(e.size) + " · " + fmtDateTime.format(new Date(e.mtime)));
                bindThumb((ImageView) v[3], e);
                return rowV;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, i, id) -> { shown.get(i).selected = !shown.get(i).selected; refreshList(); });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dateFolders = new CheckBox(this);
        dateFolders.setText("Put copies in date folders (YYYY-MM-DD)");
        root.addView(dateFolders);

        LinearLayout r4 = row();
        copyBtn = button("Copy", v -> startTransfer(false));
        moveBtn = button("Move (cut)", v -> startTransfer(true));
        cancelBtn = button("Cancel", v -> { cancel = true; log("Cancel requested..."); });
        cancelBtn.setVisibility(View.GONE);
        r4.addView(copyBtn, weight());
        r4.addView(moveBtn, weight());
        r4.addView(cancelBtn, weight());
        root.addView(r4);

        LinearLayout r5 = row();
        thumbBtn = button("Thumbnail stats", v -> thumbStats());
        reportBtn = button("Copy report", v -> copyReport());
        r5.addView(thumbBtn, weight());
        r5.addView(reportBtn, weight());
        root.addView(r5);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (130 * dp)));

        setContentView(root);
        log("SD importer v" + versionName() + " on " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        restoreFolders();
        updateStatus();
        refreshList();
        setButtons();
        if (srcTree != null) startScan();
        else log("Tap 'Card folder' and choose DCIM on the SD card, then 'Destination' for where to put the photos.");
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void setButtons() {
        boolean idle = !busy;
        srcBtn.setEnabled(idle);
        rescanBtn.setEnabled(idle && srcTree != null);
        dstBtn.setEnabled(idle);
        copyBtn.setEnabled(idle);
        moveBtn.setEnabled(idle);
        thumbBtn.setEnabled(!files.isEmpty());
        cancelBtn.setVisibility(idle ? View.GONE : View.VISIBLE);
        cancelBtn.setEnabled(!idle);
    }

    private void updateStatus() {
        status.setText("Card: " + (srcTree == null ? "(not set)" : treeLabel(srcTree))
                + "\nDestination: " + (dstTree == null ? "(not set)" : treeLabel(dstTree)));
    }

    private String treeLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        return Uri.decode(id);
    }

    // ---------------------------------------------------------------- folders

    private void pickTree(int req) {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_SRC && req != REQ_DST) || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            log("(could not persist folder permission: " + e + ")");
        }
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (req == REQ_SRC) {
            srcTree = tree;
            ed.putString(K_SRC, tree.toString());
        } else {
            dstTree = tree;
            ed.putString(K_DST, tree.toString());
        }
        ed.apply();
        updateStatus();
        setButtons();
        log((req == REQ_SRC ? "Card folder: " : "Destination: ") + treeLabel(tree));
        if (req == REQ_SRC) startScan();
    }

    private void restoreFolders() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = p.getString(K_SRC, null), d = p.getString(K_DST, null);
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission up : perms) {
            if (!up.isReadPermission() || !up.isWritePermission()) continue;
            if (s != null && up.getUri().toString().equals(s)) srcTree = up.getUri();
            if (d != null && up.getUri().toString().equals(d)) dstTree = up.getUri();
        }
        log("Remembered folders: card " + (srcTree != null ? "yes" : (s != null ? "LOST (permission gone)" : "none"))
                + ", destination " + (dstTree != null ? "yes" : (d != null ? "LOST (permission gone)" : "none")));
    }

    // ---------------------------------------------------------------- scanning

    private void startScan() {
        if (busy || srcTree == null) return;
        startWork(this::scan);
    }

    private static int typeOf(String name) {
        int i = name.lastIndexOf('.');
        String x = i > 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
        if (RAW_EXT.contains(x)) return T_RAW;
        if (x.equals("jpg") || x.equals("jpeg")) return T_JPG;
        if (x.equals("png")) return T_PNG;
        return T_OTHER;
    }

    private void scan() {
        final List<Entry> found = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        Deque<String> stack = new ArrayDeque<>();
        stack.push(DocumentsContract.getTreeDocumentId(srcTree));
        int dirs = 0;
        long t0 = SystemClock.elapsedRealtime();
        try {
            while (!stack.isEmpty()) {
                String dirId = stack.pop();
                dirs++;
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(srcTree, dirId);
                try (Cursor c = cr.query(children, proj, null, null, null)) {
                    if (c == null) continue;
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name == null || name.startsWith(".")) continue;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                            stack.push(c.getString(0));
                        } else {
                            Entry e = new Entry();
                            e.name = name;
                            e.uri = DocumentsContract.buildDocumentUriUsingTree(srcTree, c.getString(0));
                            e.size = c.isNull(3) ? 0 : c.getLong(3);
                            e.mtime = c.isNull(4) ? 0 : c.getLong(4);
                            e.type = typeOf(name);
                            found.add(e);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log("Could not read the card folder: " + ex + "\n   Is the card plugged in? Tap 'Card folder' to pick it again.");
            return;
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        long bytes = 0;
        for (Entry e : found) bytes += e.size;
        log("Scanned " + found.size() + " files (" + fmtSize(bytes) + ") in " + dirs + " folders in " + ms + " ms");
        final int n = found.size();
        ui.post(() -> {
            thumbMissing.clear();
            files.clear();
            files.addAll(found);
            applyFilter();
            setButtons();
        });
    }

    // ---------------------------------------------------------------- filtering / selection

    private void applyFilter() {
        shown.clear();
        for (Entry e : files) {
            boolean ok = typeOn[e.type] && e.mtime >= dateFrom && e.mtime <= dateTo;
            if (ok) shown.add(e);
            else e.selected = false;
        }
        Collections.sort(shown, (a, b) -> a.mtime != b.mtime ? Long.compare(b.mtime, a.mtime) : b.name.compareTo(a.name));
        refreshList();
    }

    private void refreshList() {
        long bytes = 0;
        int sel = 0;
        for (Entry e : shown) if (e.selected) { sel++; bytes += e.size; }
        selInfo.setText(sel + " selected (" + fmtSize(bytes) + ") · showing " + shown.size() + " of " + files.size() + " files · " + dateLabel);
        dateBtn.setText("Date: " + dateLabel);
        adapter.notifyDataSetChanged();
    }

    private void chooseDate() {
        final String[] opts = {"All dates", "Today", "Last 3 days", "Last 7 days", "Last 30 days", "Custom range..."};
        new AlertDialog.Builder(this).setTitle("Date (file modified date)").setItems(opts, (d, which) -> {
            Calendar c = Calendar.getInstance();
            startOfDay(c);
            switch (which) {
                case 0: setRange(0, Long.MAX_VALUE, "All dates"); break;
                case 1: setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Today"); break;
                case 2: c.add(Calendar.DAY_OF_YEAR, -2); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 3 days"); break;
                case 3: c.add(Calendar.DAY_OF_YEAR, -6); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 7 days"); break;
                case 4: c.add(Calendar.DAY_OF_YEAR, -29); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 30 days"); break;
                default: pickCustomRange();
            }
        }).show();
    }

    private static void startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private void pickCustomRange() {
        final Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (v1, y1, m1, d1) -> {
            final Calendar from = Calendar.getInstance();
            from.set(y1, m1, d1);
            startOfDay(from);
            new DatePickerDialog(this, (v2, y2, m2, d2) -> {
                Calendar to = Calendar.getInstance();
                to.set(y2, m2, d2);
                startOfDay(to);
                to.add(Calendar.DAY_OF_YEAR, 1);
                to.add(Calendar.MILLISECOND, -1);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                setRange(from.getTimeInMillis(), to.getTimeInMillis(),
                        f.format(from.getTime()) + " to " + f.format(to.getTime()));
            }, y1, m1, d1).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void setRange(long from, long to, String label) {
        dateFrom = from;
        dateTo = to;
        dateLabel = label;
        applyFilter();
    }

    // ---------------------------------------------------------------- threading helpers

    private void startWork(final Runnable r) {
        if (busy) return;
        busy = true;
        cancel = false;
        setButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                log("FAILED: " + t);
            }
            busy = false;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setButtons();
                refreshList();
            });
        }).start();
    }

    // ---------------------------------------------------------------- copy / move

    private void startTransfer(final boolean move) {
        if (busy) return;
        if (srcTree == null) { log("Pick the card folder first."); return; }
        if (dstTree == null) { log("Pick a destination folder first."); return; }
        final List<Entry> todo = new ArrayList<>();
        long bytes = 0;
        for (Entry e : shown) if (e.selected) { todo.add(e); bytes += e.size; }
        if (todo.isEmpty()) { log("Nothing selected. Tap files in the list (or 'Select all')."); return; }
        final boolean dated = dateFolders.isChecked();
        if (!move) {
            startWork(() -> transfer(todo, false, dated));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Move " + todo.size() + " files?")
                .setMessage(fmtSize(bytes) + " will be copied to the destination. Each original is deleted from the card only after its copy has been read back and checked. If anything fails, that original stays on the card.")
                .setPositiveButton("Move", (d, w) -> startWork(() -> transfer(todo, true, dated)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private DestDir openDestDir(ContentResolver cr, String docId) {
        DestDir d = new DestDir();
        d.docId = docId;
        d.docUri = DocumentsContract.buildDocumentUriUsingTree(dstTree, docId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dstTree, docId);
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) d.names.add(c.getString(0).toLowerCase(Locale.ROOT));
        }
        return d;
    }

    private DestDir subDir(ContentResolver cr, DestDir parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dstTree, parent.docId);
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = cr.query(children, proj, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    return openDestDir(cr, c.getString(0));
                }
            }
        }
        Uri made;
        try {
            made = DocumentsContract.createDocument(cr, parent.docUri, DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            throw new IOException("could not create folder " + name + ": " + e);
        }
        if (made == null) throw new IOException("could not create folder " + name);
        return openDestDir(cr, DocumentsContract.getDocumentId(made));
    }

    private static String mimeFor(String name) {
        int i = name.lastIndexOf('.');
        if (i > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(i + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private long queryLong(ContentResolver cr, Uri uri, String col) {
        try (Cursor c = cr.query(uri, new String[]{col}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        }
        return -1;
    }

    private String queryString(ContentResolver cr, Uri uri, String col) {
        try (Cursor c = cr.query(uri, new String[]{col}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    /** Copies one file, reads the copy back and compares size + CRC32. Deletes the partial copy and throws on any problem. */
    private Uri copyAndVerify(ContentResolver cr, Entry e, DestDir d, byte[] buf) throws Exception {
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
                    if (cancel) throw new IOException("cancelled");
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
            if (m == n && crc2.getValue() != crc.getValue()) {
                String tol = redactionTolerance(cr, e.uri, dest, buf);
                if (tol != null) {
                    log("  note: " + tol);
                    m = -1; // handled: fall through to success
                }
            }
            if (m != -1 && (m != n || crc2.getValue() != crc.getValue())) {
                String why = diagnose(cr, e.uri, dest, buf, crc.getValue());
                throw new IOException("verification failed: copy differs from original (wrote " + n + " B crc "
                        + Long.toHexString(crc.getValue()) + ", read back " + m + " B crc " + Long.toHexString(crc2.getValue()) + "). " + why);
            }
            String finalName = queryString(cr, dest, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (finalName != null && !finalName.equals(e.name)) log("  note: saved as '" + finalName + "' (name was changed by the system)");
            return dest;
        } catch (Exception ex) {
            if (dest != null) {
                try { DocumentsContract.deleteDocument(cr, dest); } catch (Exception ignored) { }
            }
            throw ex;
        }
    }

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
     * Called only when the read-back checksum differs. Accepts the copy ONLY if every differing byte lies inside a GPS tag
     * of the original and reads back as zero (Android's location redaction). Returns a note if accepted, null if not.
     */
    private String redactionTolerance(ContentResolver cr, Uri src, Uri dest, byte[] buf) {
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
            return diffs + " bytes inside the photo's GPS location tags read back as zero (Android hides location data in "
                    + "read-back). Everything else matched exactly.";
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
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
    private String diagnose(ContentResolver cr, Uri src, Uri dest, byte[] buf, long firstCrc) {
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
                    + "; bytes around it (the [..] one differs): original " + hexA + " | copy " + hexB : "");
        } catch (Exception ex) {
            return "Diagnosis failed: " + ex;
        }
    }

    private void transfer(List<Entry> todo, boolean move, boolean dated) {
        ContentResolver cr = getContentResolver();
        log((move ? "MOVE" : "COPY") + " " + todo.size() + " files" + (dated ? " into date folders" : "") + "...");
        DestDir root = openDestDir(cr, DocumentsContract.getTreeDocumentId(dstTree));
        Map<String, DestDir> dirs = new HashMap<>();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        byte[] buf = new byte[1 << 20];
        int copied = 0, skipped = 0, failed = 0, deleted = 0, i = 0;
        long bytes = 0, copyMs = 0, delMs = 0;
        boolean dateReported = false;
        final List<Entry> moved = new ArrayList<>();
        long t0 = SystemClock.elapsedRealtime();
        for (Entry e : todo) {
            i++;
            if (cancel) { log("Cancelled after " + (i - 1) + " of " + todo.size() + "."); break; }
            final String st = (move ? "Moving " : "Copying ") + i + "/" + todo.size() + ": " + e.name;
            ui.post(() -> selInfo.setText(st));
            try {
                DestDir d = root;
                if (dated) {
                    String dn = dayFmt.format(new Date(e.mtime));
                    d = dirs.get(dn);
                    if (d == null) { d = subDir(cr, root, dn); dirs.put(dn, d); }
                }
                if (d.names.contains(e.name.toLowerCase(Locale.ROOT))) {
                    skipped++;
                    log("  skipped (a file with this name is already there): " + e.name);
                    continue;
                }
                long f0 = SystemClock.elapsedRealtime();
                Uri dest = copyAndVerify(cr, e, d, buf);
                long f1 = SystemClock.elapsedRealtime();
                d.names.add(e.name.toLowerCase(Locale.ROOT));
                copied++;
                bytes += e.size;
                copyMs += f1 - f0;
                if (!dateReported) {
                    dateReported = true;
                    long dm = queryLong(cr, dest, DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    boolean same = dm > 0 && Math.abs(dm - e.mtime) < 2000;
                    log("  file date on the copy: " + (dm > 0 ? fmtDateTime.format(new Date(dm)) : "unknown") + " vs original "
                            + fmtDateTime.format(new Date(e.mtime)) + " -> original date " + (same ? "KEPT" : "NOT kept"));
                }
                String line = String.format(Locale.US, "  ok %s (%s, %.1f MB/s incl. verify)", e.name, fmtSize(e.size),
                        (f1 - f0) > 0 ? (e.size / 1048576.0) / ((f1 - f0) / 1000.0) : 0.0);
                if (move) {
                    long x0 = SystemClock.elapsedRealtime();
                    boolean gone;
                    try { gone = DocumentsContract.deleteDocument(cr, e.uri); } catch (Exception dex) { gone = false; log("  delete error: " + dex); }
                    delMs += SystemClock.elapsedRealtime() - x0;
                    if (gone) { deleted++; moved.add(e); line += " -> original deleted"; }
                    else line += " -> COULD NOT delete original (copy is safe)";
                }
                log(line);
            } catch (Exception ex) {
                failed++;
                log("  FAILED " + e.name + ": " + ex.getMessage() + (move ? " (original kept)" : ""));
            }
        }
        long total = SystemClock.elapsedRealtime() - t0;
        log(String.format(Locale.US, "DONE: %d copied, %d skipped, %d failed%s. %s in %.1f s = %.1f MB/s%s",
                copied, skipped, failed, move ? ", " + deleted + " originals deleted" : "",
                fmtSize(bytes), total / 1000.0, total > 0 ? (bytes / 1048576.0) / (total / 1000.0) : 0.0,
                move ? String.format(Locale.US, " (deleting took %.1f s)", delMs / 1000.0) : ""));
        if (!moved.isEmpty()) ui.post(() -> { files.removeAll(moved); applyFilter(); });
    }

    // ---------------------------------------------------------------- thumbnails

    private final android.util.LruCache<String, Bitmap> thumbCache = new android.util.LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private final java.util.concurrent.ExecutorService thumbPool = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final Set<String> thumbPending = new HashSet<>();  // UI thread only
    private final Set<String> thumbMissing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> thumbSeen = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean thumbRefreshScheduled = false;
    private final java.util.concurrent.atomic.AtomicInteger rawOk = new java.util.concurrent.atomic.AtomicInteger(),
            rawFail = new java.util.concurrent.atomic.AtomicInteger(), picOk = new java.util.concurrent.atomic.AtomicInteger(),
            picFail = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong rawMs = new java.util.concurrent.atomic.AtomicLong(),
            picMs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean rawOkLogged = new java.util.concurrent.atomic.AtomicBoolean(),
            rawFailLogged = new java.util.concurrent.atomic.AtomicBoolean(), picFailLogged = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbPool.shutdownNow();
    }

    private void bindThumb(ImageView iv, Entry e) {
        final String key = e.uri.toString();
        iv.setTag(key);
        Bitmap b = thumbCache.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(new ColorDrawable(0xFFDDDDDD));
        if (e.type == T_OTHER || thumbMissing.contains(key)) return;
        thumbSeen.put(key, SystemClock.elapsedRealtime());
        if (!thumbPending.add(key)) return;
        final Entry fe = e;
        try {
            thumbPool.execute(() -> {
                Long seen = thumbSeen.get(key);
                boolean wanted = seen != null && SystemClock.elapsedRealtime() - seen < 2500;
                if (!wanted || busy) { ui.post(() -> thumbPending.remove(key)); return; }
                final Bitmap bm = loadThumb(fe);
                ui.post(() -> {
                    thumbPending.remove(key);
                    if (bm != null) thumbCache.put(key, bm); else thumbMissing.add(key);
                    if (!thumbRefreshScheduled) {
                        thumbRefreshScheduled = true;
                        ui.postDelayed(() -> { thumbRefreshScheduled = false; adapter.notifyDataSetChanged(); }, 80);
                    }
                });
            });
        } catch (Exception ex) {
            thumbPending.remove(key);
        }
    }

    private Bitmap loadThumb(Entry e) {
        ContentResolver cr = getContentResolver();
        long t0 = SystemClock.elapsedRealtime();
        Bitmap bm = null;
        String err = null;
        try {
            if (e.type == T_RAW) {
                bm = rawPreview(cr, e.uri);
            } else {
                try { bm = DocumentsContract.getDocumentThumbnail(cr, e.uri, new Point(200, 200), null); } catch (Exception ex) { err = ex.toString(); }
                if (bm == null) bm = decodeSampled(cr, e.uri, 200);
            }
        } catch (Throwable t) {
            err = t.getMessage() != null ? t.getMessage() : t.toString();
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        if (e.type == T_RAW) {
            if (bm != null) {
                rawOk.incrementAndGet(); rawMs.addAndGet(ms);
                if (rawOkLogged.compareAndSet(false, true)) log("First RAW preview: " + e.name + " " + bm.getWidth() + "x" + bm.getHeight() + " in " + ms + " ms");
            } else {
                rawFail.incrementAndGet();
                if (rawFailLogged.compareAndSet(false, true)) log("RAW preview failed for " + e.name + ": " + err);
            }
        } else {
            if (bm != null) { picOk.incrementAndGet(); picMs.addAndGet(ms); }
            else {
                picFail.incrementAndGet();
                if (picFailLogged.compareAndSet(false, true)) log("Thumbnail failed for " + e.name + ": " + err);
            }
        }
        return bm;
    }

    private void thumbStats() {
        int ro = rawOk.get(), rf = rawFail.get(), po = picOk.get(), pf = picFail.get();
        log("Thumbnails so far: RAW " + ro + " ok / " + rf + " failed" + (ro > 0 ? ", avg " + (rawMs.get() / ro) + " ms" : "")
                + "; JPEG/PNG " + po + " ok / " + pf + " failed" + (po > 0 ? ", avg " + (picMs.get() / po) + " ms" : "")
                + "; cached " + thumbCache.size() / 1024 + " MB");
    }

    private static Bitmap decodeSampled(ContentResolver cr, Uri uri, int target) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int mx = Math.max(o.outWidth, o.outHeight);
        if (mx <= 0) return null;
        int sample = 1;
        while (mx / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, o2); }
    }

    // ---- RAW (TIFF-based, e.g. NEF): find the JPEG preview stored inside the file and decode a small version of it

    private static byte[] readAt(FileChannel ch, long pos, int len) throws IOException {
        if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("bad read length " + len);
        ByteBuffer bb = ByteBuffer.allocate(len);
        while (bb.hasRemaining()) {
            int r = ch.read(bb, pos + bb.position());
            if (r <= 0) break;
        }
        return bb.position() == len ? bb.array() : java.util.Arrays.copyOf(bb.array(), bb.position());
    }

    private static int u16(byte[] b, int o, ByteOrder bo) { return ByteBuffer.wrap(b).order(bo).getShort(o) & 0xffff; }

    private static long u32(byte[] b, int o, ByteOrder bo) { return ByteBuffer.wrap(b).order(bo).getInt(o) & 0xffffffffL; }

    private static void walkIfd(FileChannel ch, long size, ByteOrder bo, long off, int depth, boolean ifd0,
                                List<long[]> cands, int[] orient, Set<Long> seen) throws IOException {
        if (off <= 0 || off + 2 > size || depth > 3 || !seen.add(off)) return;
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
            walkIfd(ch, size, bo, subVal, depth + 1, false, cands, orient, seen);
        } else if (subCnt > 1 && subCnt <= 8 && subVal + subCnt * 4 <= size) {
            byte[] sb = readAt(ch, subVal, (int) subCnt * 4);
            for (int i = 0; i + 4 <= sb.length; i += 4) {
                walkIfd(ch, size, bo, u32(sb, i, bo), depth + 1, false, cands, orient, seen);
            }
        }
        long next = u32(buf, count * 12, bo);
        if (depth == 0 && ifd0 && next > 0) walkIfd(ch, size, bo, next, 0, false, cands, orient, seen);
    }

    private static Bitmap rawPreview(ContentResolver cr, Uri uri) throws IOException {
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
            java.util.Collections.sort(cands, (a, b) -> Long.compare(a[1], b[1]));  // smallest first
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
                if (mx >= 400) { best = decodeJpegSampled(jpg, mx); if (best != null) break; }
                else if (mx > fallbackMax) { fallback = jpg; fallbackMax = mx; }
            }
            if (best == null && fallback != null) best = decodeJpegSampled(fallback, fallbackMax);
            if (best == null) throw new IOException("no JPEG preview found inside the RAW file (" + cands.size() + " candidates)");
            return rotateForOrientation(best, orient[0]);
        }
    }

    private static Bitmap decodeJpegSampled(byte[] jpg, int mx) {
        int sample = 1;
        while (mx / (sample * 2) >= 240) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    private static Bitmap rotateForOrientation(Bitmap b, int orientation) {
        int deg = orientation == 3 ? 180 : orientation == 6 ? 90 : orientation == 8 ? 270 : 0;
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    // ---------------------------------------------------------------- misc

    private static String fmtSize(long n) {
        if (n >= 1073741824L) return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
        if (n >= 1048576L) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024) return (n / 1024) + " KB";
        return n + " B";
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
        ui.post(() -> {
            logView.append(line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
