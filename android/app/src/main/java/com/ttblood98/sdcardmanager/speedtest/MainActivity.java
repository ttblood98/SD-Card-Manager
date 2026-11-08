package com.ttblood98.sdcardmanager.speedtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offload: a gallery of the camera card. Tap to view, long-press (and drag) to select, then Copy or Move to a
 * destination chosen in the in-app folder picker. Settings hold the one-time setup (card folder, destination folders).
 */
public class MainActivity extends Activity {
    static final int REQ_CARD = 1, REQ_ROOT = 2;
    static final String[] SORT_NAMES = {"Newest first", "Oldest first", "Name A-Z", "Largest first"};

    /** A full-screen page shown above the gallery (settings, folder picker, viewer, log). */
    interface Page {
        View view();

        /** Return true if the page handled Back itself (e.g. went up a folder). */
        default boolean onBack() { return false; }

        default void refresh() { }
    }

    Ui ui;
    Prefs prefs;
    Thumbs thumbs;
    Covers covers;
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final StringBuilder report = new StringBuilder();
    private TextView logView;

    final List<Item> items = new ArrayList<>();
    final List<Item> shown = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private boolean[] types;
    private int sort;
    private long dateFrom = 0, dateTo = Long.MAX_VALUE;
    private String dateLabel = "All dates";

    private Uri card;
    private volatile boolean scanning = false, transferring = false;
    private String scanError = null;
    private boolean scannedOnce = false;
    private long lastScan = 0;
    /** Files deleted or moved since the current scan started; that scan's results must not bring them back. */
    private final Set<String> removedKeys = new HashSet<>();

    private FrameLayout host;
    private final ArrayDeque<Page> pages = new ArrayDeque<>();
    private FrameLayout overlay;

    // gallery views
    private TextView title, subtitle, closeSel, selAllBtn, sortBtn, settingsBtn, dateChip;
    private final TextView[] typeChips = new TextView[3];
    private DragListView list;
    private TextView toTop;
    private boolean toTopShown = false;
    private RowsAdapter adapter;
    private LinearLayout emptyBox, bottomBar;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        ui = new Ui(this);
        setTheme(ui.night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        prefs = new Prefs(this);
        thumbs = new Thumbs(getContentResolver(), h, this::log);
        covers = new Covers(this);
        types = prefs.types();
        sort = prefs.sort();

        getWindow().setStatusBarColor(ui.surface);
        getWindow().setNavigationBarColor(ui.surface);
        if (!ui.night) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        host = new FrameLayout(this);
        host.setBackgroundColor(ui.bg);
        host.addView(buildGallery(), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setContentView(host);

        log("Offload v" + versionName() + " on " + Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        card = prefs.card();
        log("Remembered folders: card " + (card != null ? "yes" : "no") + ", destinations " + prefs.roots().size());
        applyFilter();
        startScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scannedOnce && SystemClock.elapsedRealtime() - lastScan > 3000) startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdown();
        covers.shutdown();
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            if (transferring) Toast.makeText(this, "Use Cancel to stop the transfer", Toast.LENGTH_SHORT).show();
            else closeOverlay();
            return;
        }
        if (!pages.isEmpty()) {
            if (!pages.peek().onBack()) popPage();
            return;
        }
        if (selectedCount() > 0) { clearSelection(); return; }
        super.onBackPressed();
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ================================================================== pages

    void pushPage(Page p) {
        View v = p.view();
        v.setClickable(true);  // don't let touches fall through to the gallery
        host.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        pages.push(p);
        if (overlay != null) overlay.bringToFront();
    }

    void popPage() {
        if (pages.isEmpty()) return;
        Page p = pages.pop();
        host.removeView(p.view());
        if (p.view() == logPageView) { logView = null; logPageView = null; }
    }

    private void refreshPages() {
        for (Page p : pages) p.refresh();
    }

    /** A top bar with a Back arrow, used by every page. */
    LinearLayout pageBar(String titleText, TextView subtitleView, View... actions) {
        LinearLayout bar = ui.topBar();
        bar.addView(ui.iconButton("←", "Back", v -> onBackPressed()));
        bar.addView(ui.titleBlock(ui.text(titleText, 19, ui.text, true), subtitleView));
        for (View a : actions) bar.addView(a);
        return bar;
    }

    // ================================================================== folder access (system picker, one-time setup)

    void pickCard() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_CARD);
    }

    void pickRoot() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_ROOT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_CARD && req != REQ_ROOT) || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            log("Could not keep access to the folder: " + e.getMessage());
        }
        if (req == REQ_CARD) {
            prefs.setCard(tree);
            card = tree;
            log("Card folder: " + Prefs.rootLabel(tree));
            items.clear();
            applyFilter();
            startScan();
        } else {
            prefs.addRoot(tree);
            log("Destination folder added: " + Prefs.rootLabel(tree));
        }
        refreshPages();
    }

    // ================================================================== scanning

    void startScan() {
        card = prefs.card();
        if (card == null) { updateChrome(); return; }
        if (scanning || transferring) return;
        scanning = true;
        scanError = null;
        removedKeys.clear();
        updateChrome();
        final Uri tree = card;
        bg.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            List<Item> found = null;
            String err = null;
            try {
                found = FileOps.scan(getContentResolver(), tree);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final long ms = SystemClock.elapsedRealtime() - t0;
            final List<Item> f = found;
            final String fe = err;
            h.post(() -> {
                scanning = false;
                scannedOnce = true;
                lastScan = SystemClock.elapsedRealtime();
                if (fe != null) {
                    scanError = fe;
                    items.clear();
                    log("Could not read the card: " + fe);
                } else {
                    Set<String> sel = new HashSet<>();
                    for (Item i : items) if (i.selected) sel.add(i.key());
                    items.clear();
                    for (Item i : f) if (!removedKeys.contains(i.key())) items.add(i);
                    for (Item i : items) i.selected = sel.contains(i.key());
                    thumbs.forgetFailures();
                    long bytes = 0;
                    for (Item i : items) bytes += i.size;
                    log("Scanned " + items.size() + " files (" + Ui.fmtSize(bytes) + ") in " + ms + " ms");
                }
                applyFilter();
                refreshPages();
            });
        });
    }

    // ================================================================== filtering, sorting, grouping

    void applyFilter() {
        shown.clear();
        for (Item e : items) {
            boolean ok = types[e.type] && e.mtime >= dateFrom && e.mtime <= dateTo;
            if (ok) shown.add(e);
            else e.selected = false;
        }
        switch (sort) {
            case 1: Collections.sort(shown, (a, b) -> a.mtime != b.mtime ? Long.compare(a.mtime, b.mtime) : a.name.compareTo(b.name)); break;
            case 2: Collections.sort(shown, (a, b) -> a.name.compareToIgnoreCase(b.name)); break;
            case 3: Collections.sort(shown, (a, b) -> Long.compare(b.size, a.size)); break;
            default: Collections.sort(shown, (a, b) -> a.mtime != b.mtime ? Long.compare(b.mtime, a.mtime) : b.name.compareTo(a.name));
        }
        rebuildRows();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateChrome();
    }

    private static final class Header {
        String label;
        int start, end;
    }

    private static final class TileRow {
        int start, count;
    }

    /** Columns shown right now: the saved setting, or the target of a pinch in progress. */
    private int gridCols = -1;

    int cols() { return gridCols > 0 ? gridCols : prefs.columns(); }

    private void rebuildRows() {
        rows.clear();
        buildRows(cols(), rows);
    }

    private void buildRows(int cols, List<Object> rows) {
        boolean group = sort == 0 || sort == 1;
        int i = 0;
        while (i < shown.size()) {
            int end = shown.size();
            if (group) {
                int day = dayKey(shown.get(i).mtime);
                end = i;
                while (end < shown.size() && dayKey(shown.get(end).mtime) == day) end++;
                Header hd = new Header();
                hd.label = dayLabel(shown.get(i).mtime);
                hd.start = i;
                hd.end = end;
                rows.add(hd);
            }
            for (int s = i; s < end; s += cols) {
                TileRow tr = new TileRow();
                tr.start = s;
                tr.count = Math.min(cols, end - s);
                rows.add(tr);
            }
            i = end;
        }
    }

    private static int dayKey(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private static String dayLabel(long ms) {
        int k = dayKey(ms);
        Calendar now = Calendar.getInstance();
        if (k == dayKey(now.getTimeInMillis())) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (k == dayKey(now.getTimeInMillis())) return "Yesterday";
        return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(new Date(ms));
    }

    private void toggleType(int t) {
        types[t] = !types[t];
        prefs.setTypes(types);
        applyFilter();
    }

    private void resetFilters() {
        for (int i = 0; i < 3; i++) types[i] = true;
        prefs.setTypes(types);
        dateFrom = 0;
        dateTo = Long.MAX_VALUE;
        dateLabel = "All dates";
        applyFilter();
    }

    private void chooseSort() {
        new AlertDialog.Builder(this).setTitle("Sort by")
                .setSingleChoiceItems(SORT_NAMES, sort, (d, which) -> {
                    sort = which;
                    prefs.setSort(which);
                    applyFilter();
                    d.dismiss();
                }).show();
    }

    private void chooseDate() {
        final String[] opts = {"All dates", "Today", "Yesterday", "Last 3 days", "Last 7 days", "Last 30 days", "Pick dates…"};
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, dateChip);
        for (int i = 0; i < opts.length; i++) {
            android.view.MenuItem mi = pm.getMenu().add(0, i, i, opts[i]);
            mi.setChecked(opts[i].equals(dateLabel));
        }
        pm.getMenu().setGroupCheckable(0, true, true);
        pm.setOnMenuItemClickListener(item -> {
            Calendar c = Calendar.getInstance();
            startOfDay(c);
            switch (item.getItemId()) {
                case 0: setRange(0, Long.MAX_VALUE, "All dates"); break;
                case 1: setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Today"); break;
                case 2: {
                    long end = c.getTimeInMillis() - 1;
                    c.add(Calendar.DAY_OF_YEAR, -1);
                    setRange(c.getTimeInMillis(), end, "Yesterday");
                    break;
                }
                case 3: c.add(Calendar.DAY_OF_YEAR, -2); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 3 days"); break;
                case 4: c.add(Calendar.DAY_OF_YEAR, -6); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 7 days"); break;
                case 5: c.add(Calendar.DAY_OF_YEAR, -29); setRange(c.getTimeInMillis(), Long.MAX_VALUE, "Last 30 days"); break;
                default: pickCustomRange();
            }
            return true;
        });
        pm.show();
    }

    private static void startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private void pickCustomRange() {
        Set<Integer> days = new HashSet<>();
        long newest = 0;
        Calendar c = Calendar.getInstance();
        for (Item e : items) {
            if (!types[e.type]) continue;
            c.setTimeInMillis(e.mtime);
            days.add(DateRangeDialog.ymd(c));
            newest = Math.max(newest, e.mtime);
        }
        new DateRangeDialog(this, days, newest, this::setRange).show();
    }

    private void setRange(long from, long to, String label) {
        dateFrom = from;
        dateTo = to;
        dateLabel = label;
        applyFilter();
    }

    // ================================================================== selection

    int selectedCount() {
        int n = 0;
        for (Item e : shown) if (e.selected) n++;
        return n;
    }

    List<Item> selectedItems() {
        List<Item> out = new ArrayList<>();
        for (Item e : shown) if (e.selected) out.add(e);
        return out;
    }

    void onSelectionChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
        updateChrome();
    }

    void clearSelection() {
        for (Item e : items) e.selected = false;
        onSelectionChanged();
    }

    private void selectAllShown() {
        boolean all = selectedCount() == shown.size();
        for (Item e : shown) e.selected = !all;
        onSelectionChanged();
    }

    private void toggleRange(int start, int end) {
        boolean all = true;
        for (int i = start; i < end; i++) if (!shown.get(i).selected) { all = false; break; }
        for (int i = start; i < end; i++) shown.get(i).selected = !all;
        onSelectionChanged();
    }

    private void onTileTap(int idx) {
        if (idx < 0 || idx >= shown.size()) return;
        if (selectedCount() > 0) {
            shown.get(idx).selected = !shown.get(idx).selected;
            onSelectionChanged();
        } else {
            pushPage(new Viewer(this, idx));
        }
    }

    // ================================================================== gallery UI

    private View buildGallery() {
        LinearLayout root = ui.vbox();
        root.setBackgroundColor(ui.bg);

        LinearLayout bar = ui.topBar();
        closeSel = ui.iconButton("✕", "Clear selection", v -> clearSelection());
        title = ui.text("Offload", 20, ui.text, true);
        subtitle = ui.text("", 13, ui.textDim, false);
        selAllBtn = ui.flatButton("Select all", v -> selectAllShown());
        sortBtn = ui.iconButton("⇅", "Sort", v -> chooseSort());
        settingsBtn = ui.iconButton("⚙", "Settings", v -> pushPage(new SettingsPage()));
        bar.addView(closeSel);
        LinearLayout tb = ui.titleBlock(title, subtitle);
        tb.setPadding(ui.px(12), 0, ui.px(8), 0);
        bar.addView(tb);
        bar.addView(selAllBtn);
        bar.addView(sortBtn);
        bar.addView(settingsBtn);
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout chips = ui.hbox();
        chips.setBackgroundColor(ui.bg);
        chips.setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(6));
        for (int i = 0; i < 3; i++) {
            final int t = i;
            typeChips[i] = ui.chip(Item.TYPE_NAMES[i], v -> toggleType(t));
            chips.addView(typeChips[i]);
        }
        dateChip = ui.text("All dates", 14, ui.text, false);
        dateChip.setSingleLine(true);
        dateChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dateChip.setGravity(Gravity.CENTER_VERTICAL);
        dateChip.setMinHeight(ui.px(36));
        dateChip.setPadding(ui.px(12), 0, ui.px(10), 0);
        dateChip.setOnClickListener(v -> chooseDate());
        dateChip.setContentDescription("Date filter");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ui.px(36), 1f);
        dlp.setMargins(ui.px(2), 0, 0, 0);
        chips.addView(dateChip, dlp);
        root.addView(chips, Ui.lp(Ui.MATCH, Ui.WRAP));

        FrameLayout content = new FrameLayout(this);
        list = new DragListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, ui.px(12));
        adapter = new RowsAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        emptyBox = ui.vbox();
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setPadding(ui.px(32), ui.px(32), ui.px(32), ui.px(32));
        content.addView(emptyBox, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER));

        // small "back to top" button, bottom centre, shown once you have scrolled down a little
        toTop = ui.text("\u2191", 18, ui.text, true);
        toTop.setGravity(Gravity.CENTER);
        toTop.setBackground(ui.round(ui.surface, 18, ui.divider));
        toTop.setElevation(ui.px(4));
        toTop.setContentDescription("Back to top");
        toTop.setAlpha(0f);
        toTop.setVisibility(View.GONE);
        toTop.setOnClickListener(v -> scrollToTop());
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ui.px(36), ui.px(36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tp.setMargins(0, 0, 0, ui.px(14));
        content.addView(toTop, tp);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) { }

            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                showToTop(first > 1);
            }
        });
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        bottomBar = ui.hbox();
        bottomBar.setBackgroundColor(ui.surface);
        bottomBar.setElevation(ui.px(8));
        bottomBar.setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10));
        TextView delBtn = ui.secondaryButton("Delete", v -> confirmDelete(selectedItems()));
        delBtn.setTextColor(ui.danger);
        android.graphics.drawable.Drawable trash = trashIcon(ui.danger, 18);
        if (trash != null) {
            delBtn.setCompoundDrawablesRelative(trash, null, null, null);
            delBtn.setCompoundDrawablePadding(ui.px(6));
        }
        TextView copyBtn = ui.secondaryButton("Copy", v -> startTransferFlow(false));
        TextView moveBtn = ui.primaryButton("Move", v -> startTransferFlow(true));
        bottomBar.addView(delBtn, Ui.weight(1.15f));
        LinearLayout.LayoutParams bp = Ui.weight(1);
        bp.setMargins(ui.px(8), 0, 0, 0);
        bottomBar.addView(copyBtn, bp);
        LinearLayout.LayoutParams bp2 = Ui.weight(1);
        bp2.setMargins(ui.px(8), 0, 0, 0);
        bottomBar.addView(moveBtn, bp2);
        root.addView(bottomBar, Ui.lp(Ui.MATCH, Ui.WRAP));
        return root;
    }

    private void showToTop(boolean show) {
        if (toTop == null || show == toTopShown) return;
        toTopShown = show;
        toTop.animate().cancel();
        if (show) {
            toTop.setVisibility(View.VISIBLE);
            toTop.animate().alpha(1f).setDuration(150).start();
        } else {
            toTop.animate().alpha(0f).setDuration(150).withEndAction(() -> { if (!toTopShown) toTop.setVisibility(View.GONE); }).start();
        }
    }

    private void scrollToTop() {
        if (list.getFirstVisiblePosition() > 30) list.setSelection(30);  // long list: jump close, then glide the rest
        list.smoothScrollToPositionFromTop(0, 0, 250);
    }

    private void updateChrome() {
        if (title == null) return;
        int n = 0;
        long selBytes = 0, shownBytes = 0;
        for (Item e : shown) { shownBytes += e.size; if (e.selected) { n++; selBytes += e.size; } }
        boolean sel = n > 0;
        closeSel.setVisibility(sel ? View.VISIBLE : View.GONE);
        selAllBtn.setVisibility(sel ? View.VISIBLE : View.GONE);
        selAllBtn.setText(n == shown.size() ? "Select none" : "Select all");
        sortBtn.setVisibility(sel ? View.GONE : View.VISIBLE);
        settingsBtn.setVisibility(sel ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (sel) {
            title.setText(n + " selected");
            subtitle.setText(Ui.fmtSize(selBytes));
        } else {
            title.setText("Offload");
            if (scanning && items.isEmpty()) subtitle.setText("Reading card…");
            else if (card == null) subtitle.setText("No card folder yet");
            else if (shown.size() == items.size()) subtitle.setText(items.size() + " files · " + Ui.fmtSize(shownBytes));
            else subtitle.setText(shown.size() + " of " + items.size() + " files · " + Ui.fmtSize(shownBytes));
        }
        for (int i = 0; i < 3; i++) ui.setChip(typeChips[i], types[i]);
        boolean dated = dateFrom != 0 || dateTo != Long.MAX_VALUE;
        dateChip.setText("📅  " + dateLabel + "  ▾");
        dateChip.setTextColor(dated ? ui.onAccent : ui.text);
        dateChip.setBackground(ui.ripple(ui.round(dated ? ui.accent : ui.surface, 10, dated ? 0 : ui.divider), ui.rippleColor()));
        updateEmpty();
    }

    private void updateEmpty() {
        emptyBox.removeAllViews();
        String head = null, body = null;
        List<View> buttons = new ArrayList<>();
        if (card == null) {
            head = prefs.cardWasSet() ? "Card folder access was lost" : "Welcome to Offload";
            body = "Choose the DCIM folder on your camera's SD card. You only need to do this once; it is remembered.";
            buttons.add(ui.primaryButton("Choose card folder", v -> pickCard()));
        } else if (scanError != null) {
            head = "Can't read the card";
            body = "Is the SD card plugged in? (" + scanError + ")";
            buttons.add(ui.primaryButton("Try again", v -> startScan()));
            buttons.add(ui.secondaryButton("Choose a different folder", v -> pickCard()));
        } else if (scanning && items.isEmpty()) {
            head = "Reading card…";
        } else if (items.isEmpty() && scannedOnce) {
            head = "No files here";
            body = "The card folder is empty. Did you pick the right folder?";
            buttons.add(ui.secondaryButton("Choose a different folder", v -> pickCard()));
        } else if (shown.isEmpty() && !items.isEmpty()) {
            head = "Nothing matches these filters";
            buttons.add(ui.secondaryButton("Reset filters", v -> resetFilters()));
        }
        if (head == null) { emptyBox.setVisibility(View.GONE); return; }
        emptyBox.setVisibility(View.VISIBLE);
        TextView h1 = ui.text(head, 20, ui.text, true);
        h1.setGravity(Gravity.CENTER);
        emptyBox.addView(h1, Ui.lp(Ui.MATCH, Ui.WRAP));
        if (body != null) {
            TextView b1 = ui.text(body, 15, ui.textDim, false);
            b1.setGravity(Gravity.CENTER);
            b1.setPadding(0, ui.px(8), 0, ui.px(8));
            emptyBox.addView(b1, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        for (View bt : buttons) {
            LinearLayout.LayoutParams p = Ui.lp(Ui.MATCH, Ui.WRAP);
            p.setMargins(0, ui.px(12), 0, 0);
            emptyBox.addView(bt, p);
        }
    }

    // ---- grid rows

    private final class RowHolder {
        int cols;
        FrameLayout[] frames;
        ImageView[] imgs;
        TextView[] badges, checks, labels;
        int[] index;
    }

    private final class HeaderHolder {
        TextView label, count, check;
        Header header;
    }

    private final class RowsAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof Header ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return false; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Object r = rows.get(pos);
            boolean selMode = selectedCount() > 0;
            if (r instanceof Header) {
                Header hd = (Header) r;
                HeaderHolder hh;
                if (convert == null) {
                    LinearLayout row = ui.hbox();
                    row.setPadding(ui.px(16), ui.px(8), ui.px(8), 0);
                    row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, headerHeight()));
                    hh = new HeaderHolder();
                    hh.label = ui.text("", 15, ui.text, true);
                    hh.count = ui.text("", 13, ui.textDim, false);
                    hh.check = ui.text("", 14, ui.accent, true);
                    hh.check.setGravity(Gravity.CENTER);
                    hh.check.setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6));
                    hh.check.setBackground(ui.ripple(null, ui.rippleColor()));
                    final HeaderHolder fh = hh;
                    hh.check.setOnClickListener(v -> toggleRange(fh.header.start, fh.header.end));
                    row.addView(hh.label);
                    LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.WRAP);
                    cp.setMargins(ui.px(8), 0, 0, 0);
                    row.addView(hh.count, cp);
                    row.addView(ui.spacer());
                    row.addView(hh.check);
                    row.setTag(hh);
                    convert = row;
                } else {
                    hh = (HeaderHolder) convert.getTag();
                }
                hh.header = hd;
                hh.label.setText(hd.label);
                int n = hd.end - hd.start;
                hh.count.setText(String.valueOf(n));
                boolean all = true;
                for (int i = hd.start; i < hd.end; i++) if (!shown.get(i).selected) { all = false; break; }
                hh.check.setText(all ? "Deselect" : "Select");
                return convert;
            }

            TileRow tr = (TileRow) r;
            int cols = cols();
            int w = list.getWidth() > 0 ? list.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int tile = w / cols;
            RowHolder rh = convert != null ? (RowHolder) convert.getTag() : null;
            if (rh == null || rh.cols != cols) {
                LinearLayout row = ui.hbox();
                rh = new RowHolder();
                rh.cols = cols;
                rh.frames = new FrameLayout[cols];
                rh.imgs = new ImageView[cols];
                rh.badges = new TextView[cols];
                rh.checks = new TextView[cols];
                rh.labels = new TextView[cols];
                rh.index = new int[cols];
                for (int c = 0; c < cols; c++) buildTile(row, rh, c);
                row.setTag(rh);
                row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, tile));
                convert = row;
            }
            ViewGroup.LayoutParams lp = convert.getLayoutParams();
            if (lp == null || lp.height != tile) convert.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, tile));
            for (int c = 0; c < cols; c++) {
                if (c >= tr.count) { rh.frames[c].setVisibility(View.INVISIBLE); rh.index[c] = -1; continue; }
                int idx = tr.start + c;
                rh.index[c] = idx;
                Item e = shown.get(idx);
                rh.frames[c].setVisibility(View.VISIBLE);
                rh.frames[c].setContentDescription(e.name + (e.selected ? ", selected" : ""));
                thumbs.bind(rh.imgs[c], e);
                float scale = e.selected ? 0.84f : 1f;
                rh.imgs[c].setScaleX(scale);
                rh.imgs[c].setScaleY(scale);
                rh.frames[c].setBackgroundColor(e.selected ? ui.selected : 0);
                String badge = e.type == Item.T_RAW ? (e.ext().isEmpty() ? "RAW" : e.ext()) : e.type == Item.T_JPG ? "JPG" : e.ext();
                rh.badges[c].setText(badge);
                rh.badges[c].setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
                rh.labels[c].setVisibility(e.type == Item.T_OTHER ? View.VISIBLE : View.GONE);
                rh.labels[c].setText(e.ext().isEmpty() ? "FILE" : e.ext());
                TextView ck = rh.checks[c];
                if (!selMode) {
                    ck.setVisibility(View.GONE);
                } else {
                    ck.setVisibility(View.VISIBLE);
                    if (e.selected) {
                        ck.setText("✓");
                        ck.setBackground(oval(ui.accent, 0));
                    } else {
                        ck.setText("");
                        ck.setBackground(oval(0x33000000, 0xFFFFFFFF));
                    }
                }
            }
            return convert;
        }
    }

    private android.graphics.drawable.GradientDrawable oval(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(ui.px(2), stroke);
        return g;
    }

    private void buildTile(LinearLayout row, RowHolder rh, int c) {
        FrameLayout f = new FrameLayout(this);
        int m = Math.max(1, ui.px(1));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, Ui.MATCH, 1f);
        fp.setMargins(m, m, m, m);
        row.addView(f, fp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(ui.surface2);
        f.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        TextView label = ui.text("", 15, ui.textDim, true);
        label.setGravity(Gravity.CENTER);
        f.addView(label, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        TextView badge = ui.text("", 10, 0xFFFFFFFF, true);
        badge.setBackground(ui.round(0x99000000, 4, 0));
        badge.setPadding(ui.px(4), ui.px(1), ui.px(4), ui.px(1));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(ui.px(4), 0, 0, ui.px(4));
        f.addView(badge, blp);

        TextView check = ui.text("", 13, ui.onAccent, true);
        check.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ui.px(24), ui.px(24), Gravity.TOP | Gravity.END);
        clp.setMargins(0, ui.px(6), ui.px(6), 0);
        f.addView(check, clp);

        f.setForeground(ui.ripple(null, 0x33FFFFFF));
        final RowHolder h2 = rh;
        final int col = c;
        f.setOnClickListener(v -> onTileTap(h2.index[col]));
        f.setOnLongClickListener(v -> {
            int idx = h2.index[col];
            if (idx >= 0) list.startDrag(idx);
            return true;
        });
        rh.frames[c] = f;
        rh.imgs[c] = iv;
        rh.labels[c] = label;
        rh.badges[c] = badge;
        rh.checks[c] = check;
    }

    // ---- long-press + drag selection (like a phone gallery), with auto-scroll at the edges

    private final class DragListView extends ListView {
        private boolean dragging, autoScrolling;
        private int anchor, lastIdx, lo, hi;
        private boolean target;
        private boolean[] snap;
        private float lastX, lastY;
        private final Runnable tick = this::autoScrollTick;

        private final android.view.ScaleGestureDetector pinch;
        private boolean pinching, pinchCancelSent;
        private float pinchCum = 1f;

        DragListView(Context c) {
            super(c);
            pinch = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                    pinching = true;
                    pinchCum = 1f;
                    pinchBegin();
                    return true;
                }

                @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                    pinchCum *= d.getScaleFactor();
                    pinchUpdate(pinchCum, d.getFocusX(), d.getFocusY());
                    return true;
                }

                @Override public void onScaleEnd(android.view.ScaleGestureDetector d) {
                    pinching = false;
                    pinchEnd();
                }
            });
        }

        void startDrag(int idx) {
            if (idx < 0 || idx >= shown.size()) return;
            dragging = true;
            anchor = idx;
            lastIdx = -1;
            target = !shown.get(idx).selected;
            snap = new boolean[shown.size()];
            for (int i = 0; i < snap.length; i++) snap[i] = shown.get(i).selected;
            lo = hi = idx;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            applyRange(idx);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (!dragging) {
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) pinchCancelSent = false;
                pinch.onTouchEvent(ev);
                if (pinching || ev.getPointerCount() > 1 || pinchCancelSent) {
                    if (!pinchCancelSent) {
                        MotionEvent cancelEv = MotionEvent.obtain(ev);
                        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancelEv);
                        cancelEv.recycle();
                        pinchCancelSent = true;
                    }
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) { pinchCancelSent = false; pinching = false; }
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
            int a = ev.getActionMasked();
            lastX = ev.getX();
            lastY = ev.getY();
            if (a == MotionEvent.ACTION_MOVE) {
                updateFromPoint();
                if (!autoScrolling && edgeSpeed() != 0) { autoScrolling = true; postOnAnimation(tick); }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                autoScrolling = false;
                removeCallbacks(tick);
            }
            return true;
        }

        private int edgeSpeed() {
            float edge = ui.px(72), max = ui.px(22);
            if (lastY < edge) return -(int) Math.max(2, (edge - lastY) / edge * max);
            if (lastY > getHeight() - edge) return (int) Math.max(2, (lastY - (getHeight() - edge)) / edge * max);
            return 0;
        }

        private void autoScrollTick() {
            if (!dragging) { autoScrolling = false; return; }
            int dy = edgeSpeed();
            if (dy == 0) { autoScrolling = false; return; }
            scrollListBy(dy);
            updateFromPoint();
            postOnAnimation(tick);
        }

        private void updateFromPoint() {
            int idx = indexAt(lastX, lastY);
            if (idx >= 0 && idx != lastIdx) applyRange(idx);
        }

        private int indexAt(float x, float y) {
            int cols = cols();
            for (int c = 0; c < getChildCount(); c++) {
                View ch = getChildAt(c);
                if (y < ch.getTop() || y >= ch.getBottom()) continue;
                int pos = getFirstVisiblePosition() + c;
                if (pos < 0 || pos >= rows.size()) return -1;
                Object r = rows.get(pos);
                if (!(r instanceof TileRow)) return -1;
                TileRow tr = (TileRow) r;
                int col = (int) (x / (getWidth() / (float) cols));
                col = Math.max(0, Math.min(col, tr.count - 1));
                return tr.start + col;
            }
            return -1;
        }

        private void applyRange(int idx) {
            int nlo = Math.min(anchor, idx), nhi = Math.max(anchor, idx);
            int from = Math.min(lo, nlo), to = Math.max(hi, nhi);
            for (int i = from; i <= to && i < shown.size(); i++) {
                shown.get(i).selected = (i >= nlo && i <= nhi) ? target : (i < snap.length && snap[i]);
            }
            lo = nlo;
            hi = nhi;
            lastIdx = idx;
            onSelectionChanged();
        }
    }

    // ---- pinch-to-resize. While pinching, an overlay draws every visible thumbnail gliding from its place in the
    // old grid to its place in the new one, in step with the fingers (the photo under the fingers stays under them).
    // The real grid is only swapped when the fingers lift, already lined up with where the photos ended up.
    // Spreading = fewer, bigger thumbnails; pinching = more, smaller. Release past halfway to keep the change.

    int headerHeight() { return ui.px(48); }

    /** Where every row, photo and day header sits for a given column count (absolute y, from the top of the list). */
    private final class GridGeom {
        final int cols, tile, width;
        final List<Object> rows = new ArrayList<>();
        final int[] rowY, itemY, itemCol;
        final List<Header> headers = new ArrayList<>();
        final List<Integer> headerY = new ArrayList<>();
        final int total;

        GridGeom(int cols) {
            this.cols = cols;
            width = list.getWidth();
            tile = width / cols;
            buildRows(cols, rows);
            rowY = new int[rows.size() + 1];
            itemY = new int[shown.size()];
            itemCol = new int[shown.size()];
            int y = 0;
            for (int r = 0; r < rows.size(); r++) {
                rowY[r] = y;
                Object o = rows.get(r);
                if (o instanceof Header) {
                    headers.add((Header) o);
                    headerY.add(y);
                    y += headerHeight();
                } else {
                    TileRow tr = (TileRow) o;
                    for (int c = 0; c < tr.count; c++) { itemY[tr.start + c] = y; itemCol[tr.start + c] = c; }
                    y += tile;
                }
            }
            rowY[rows.size()] = y;
            total = y + list.getPaddingBottom();
        }

        float x(int i) { return itemCol[i] * (width / (float) cols); }

        float maxScroll(int viewH) { return Math.max(0, total - viewH); }

        /** First photo whose tile reaches below y. */
        int firstBelow(float y) {
            int lo = 0, hi = itemY.length;
            while (lo < hi) { int m = (lo + hi) >>> 1; if (itemY[m] + tile <= y) lo = m + 1; else hi = m; }
            return lo;
        }

        /** Last photo whose tile starts above y. */
        int lastAbove(float y) {
            int lo = -1, hi = itemY.length - 1;
            while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (itemY[m] < y) lo = m; else hi = m - 1; }
            return lo;
        }

        int rowAt(float y) {
            int lo = 0, hi = rows.size() - 1;
            while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (rowY[m] <= y) lo = m; else hi = m - 1; }
            return lo;
        }
    }

    /** The overlay that draws the transition between two column counts. */
    private final class GridMorph extends View {
        GridGeom from, to;
        float scrollFrom, scrollTo, p, fx, fy;
        final android.graphics.Paint bmpPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        final android.graphics.Paint fill = new android.graphics.Paint();
        final android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Rect src = new android.graphics.Rect();
        final android.graphics.RectF dst = new android.graphics.RectF();

        GridMorph(Context c) {
            super(c);
            text.setColor(ui.text);
            text.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
            text.setFakeBoldText(true);
        }

        /** Sets the next column count and lines it up so the photo under (fx, fy) stays under the fingers. */
        boolean target(int cols, float fx, float fy) {
            if (cols < 2 || cols > 7 || cols == from.cols) return false;
            this.fx = fx;
            this.fy = fy;
            to = new GridGeom(cols);
            int h = getHeight() > 0 ? getHeight() : list.getHeight();
            int a = anchor(fx, fy, h);
            float oy = from.itemY[a] - scrollFrom;                  // anchor's top on screen now
            float k = to.tile / (float) from.tile;
            float ny = fy - (fy - oy) * k;                            // where its top should land
            scrollTo = Math.max(0, Math.min(to.maxScroll(h), to.itemY[a] - ny));
            return true;
        }

        private int anchor(float fx, float fy, int h) {
            float ay = fy + scrollFrom;
            int first = from.firstBelow(scrollFrom), last = from.lastAbove(scrollFrom + h);
            int best = Math.max(0, Math.min(first, shown.size() - 1));
            float bestD = Float.MAX_VALUE;
            for (int i = Math.max(0, first); i <= last && i < shown.size(); i++) {
                float cx = from.x(i) + from.tile / 2f, cy = from.itemY[i] + from.tile / 2f;
                float d = (cx - fx) * (cx - fx) + (cy - ay) * (cy - ay);
                if (d < bestD) { bestD = d; best = i; }
            }
            return best;
        }

        /** The old target becomes the starting point (used when the pinch keeps going to the next count). */
        void advance() {
            from = to;
            scrollFrom = scrollTo;
            to = null;
            p = 0f;
        }

        void setProgress(float v) {
            p = v;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            cv.drawColor(ui.bg);
            if (from == null) return;
            int h = getHeight();
            float t = to == null ? 0f : p;
            GridGeom b = to == null ? from : to;
            float sb = to == null ? scrollFrom : scrollTo;
            int gap = Math.max(1, ui.px(1));

            // day headers
            for (int k = 0; k < from.headers.size() && k < b.headers.size(); k++) {
                float y0 = from.headerY.get(k) - scrollFrom, y1 = b.headerY.get(k) - sb;
                float y = y0 + (y1 - y0) * t;
                if (y > h || y + headerHeight() < 0) continue;
                text.setAlpha(255);
                cv.drawText(from.headers.get(k).label, ui.px(16), y + headerHeight() * 0.66f, text);
            }

            // photos: everything visible in either layout
            int a0 = from.firstBelow(scrollFrom), a1 = from.lastAbove(scrollFrom + h);
            int b0 = b.firstBelow(sb), b1 = b.lastAbove(sb + h);
            int lo = Math.min(a0, b0), hi = Math.max(a1, b1);
            if (hi - lo > 600) { lo = Math.max(lo, Math.min(a0, b0)); hi = lo + 600; }
            for (int i = Math.max(0, lo); i <= hi && i < shown.size(); i++) {
                float ox = from.x(i), oy = from.itemY[i] - scrollFrom, os = from.tile;
                float nx = b.x(i), ny = b.itemY[i] - sb, ns = b.tile;
                boolean inOld = oy < h && oy + os > 0, inNew = ny < h && ny + ns > 0;
                float alpha = inOld && inNew ? 1f : inOld ? 1f - t : t;
                if (alpha <= 0.01f) continue;
                Item e = shown.get(i);
                if (Math.abs(nx - ox) > from.width / 2f) {
                    // wraps to another row: fade out where it was and in where it lands, both zooming with the grid,
                    // instead of flying across the other photos
                    float k = ns / os, z1 = 1f + (k - 1f) * t, z2 = 1f / k + (1f - 1f / k) * t;
                    drawTile(cv, e, fx + (ox - fx) * z1, fy + (oy - fy) * z1, os * z1, alpha * (1f - smooth(0f, 0.6f, t)), gap);
                    drawTile(cv, e, fx + (nx - fx) * z2, fy + (ny - fy) * z2, ns * z2, alpha * smooth(0.4f, 1f, t), gap);
                } else {
                    drawTile(cv, e, ox + (nx - ox) * t, oy + (ny - oy) * t, os + (ns - os) * t, alpha, gap);
                }
            }
        }

        private void drawTile(android.graphics.Canvas cv, Item e, float x, float y, float sz, float alpha, int gap) {
            if (alpha <= 0.01f) return;
            dst.set(x + gap, y + gap, x + sz - gap, y + sz - gap);
            android.graphics.Bitmap bm = thumbs.cached(e);
            if (bm != null) {
                int bw = bm.getWidth(), bh = bm.getHeight(), side = Math.min(bw, bh);
                src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
                bmpPaint.setAlpha((int) (255 * alpha));
                cv.drawBitmap(bm, src, dst, bmpPaint);
            } else {
                fill.setColor(ui.surface2);
                fill.setAlpha((int) (255 * alpha));
                cv.drawRect(dst, fill);
            }
            if (e.selected) {
                fill.setColor(ui.selected);
                fill.setAlpha((int) (alphaOf(ui.selected) * alpha));
                cv.drawRect(dst, fill);
            }
        }
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static int alphaOf(int c) { return (c >>> 24) & 0xff; }

    private GridMorph morph;
    private int pinchDir = 0;
    private float pinchBase = 1f, morphP = 0f;
    private android.animation.ValueAnimator pinchAnim;

    private void pinchBegin() {
        if (pinchAnim != null) pinchAnim.end();
        pinchDir = 0;
        pinchBase = 1f;
    }

    /** How far the fingers have gone toward the target column count (0 = start, 1 = there). */
    private float morphProgress(float eff) {
        float ratio = morph.from.cols / (float) morph.to.cols;  // > 1 when thumbnails get bigger
        return ratio > 1 ? (eff - 1f) / (ratio - 1f) : (1f - eff) / (1f - ratio);
    }

    private void pinchUpdate(float cum, float fx, float fy) {
        if (morph == null) {
            float rel = cum / pinchBase;
            int dir = rel > 1.05f ? -1 : rel < 0.95f ? +1 : 0;  // -1 = fewer columns
            if (dir == 0) return;
            pinchDir = dir;
            pinchBase = cum;
            if (!startMorph(dir, fx, fy)) return;
        }
        if (morph.to == null) return;
        float p = morphProgress(cum / pinchBase);
        if (p < -0.03f) {  // came back past this step's start: aim the other way instead (no visible change at the start)
            int other = morph.from.cols - pinchDir;
            if (other >= 2 && other <= 7 && morph.target(other, fx, fy)) {
                pinchDir = -pinchDir;
                p = morphProgress(cum / pinchBase);
            }
        }
        if (p > 1.12f) {  // kept going past the target: make it the new start and aim one more step
            int next = morph.to.cols + pinchDir;
            if (next >= 2 && next <= 7) {
                morph.advance();
                morph.target(next, fx, fy);
                pinchBase = cum;
                p = 0f;
                list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        // a little give past either end
        float shown = p < 0 ? p * 0.25f : p > 1 ? 1f + (p - 1f) * 0.25f : p;
        morphP = Math.max(-0.08f, Math.min(1.08f, shown));
        morph.setProgress(morphP);
    }

    private boolean startMorph(int dir, float fx, float fy) {
        if (shown.isEmpty() || list.getWidth() == 0 || list.getChildCount() == 0) return false;
        int cols = cols();
        if (cols + dir < 2 || cols + dir > 7) return false;
        GridMorph m = new GridMorph(this);
        m.from = new GridGeom(cols);
        int fp = list.getFirstVisiblePosition();
        m.scrollFrom = (fp < m.from.rowY.length ? m.from.rowY[fp] : 0) - list.getChildAt(0).getTop();
        if (!m.target(cols + dir, fx, fy)) return false;
        FrameLayout parent = (FrameLayout) list.getParent();
        parent.addView(m, 1, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        morph = m;
        morphP = 0f;
        m.setProgress(0f);
        return true;
    }

    private void pinchEnd() {
        if (morph == null) return;
        final GridMorph m = morph;
        final boolean keep = m.to != null && morphP >= 0.5f;
        float target = keep ? 1f : 0f;
        pinchAnim = android.animation.ValueAnimator.ofFloat(morphP, target);
        pinchAnim.setDuration((long) (100 + 180 * Math.abs(target - morphP)));
        pinchAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        pinchAnim.addUpdateListener(an -> { morphP = (float) an.getAnimatedValue(); m.setProgress(morphP); });
        pinchAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean done;

            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (done) return;
                done = true;
                finishMorph(m, keep);
            }
        });
        pinchAnim.start();
    }

    /** Puts the real grid exactly where the overlay ended, then removes the overlay once the grid has drawn. */
    private void finishMorph(final GridMorph m, boolean keep) {
        GridGeom g = keep && m.to != null ? m.to : m.from;
        float scroll = keep && m.to != null ? m.scrollTo : m.scrollFrom;
        if (g.cols != prefs.columns()) list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        prefs.setColumns(g.cols);
        gridCols = -1;
        rebuildRows();
        adapter.notifyDataSetChanged();
        if (!rows.isEmpty()) {
            int r = g.rowAt(scroll);
            list.setSelectionFromTop(r, (int) (g.rowY[r] - scroll));
        }
        if (morph == m) morph = null;
        list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                list.postOnAnimation(() -> { if (m.getParent() != null) ((ViewGroup) m.getParent()).removeView(m); });
                return true;
            }
        });
        list.invalidate();
    }

    // ================================================================== copy / move

    private void startTransferFlow(boolean move) {
        List<Item> sel = selectedItems();
        if (sel.isEmpty() || transferring) return;
        pushPage(new DestPicker(this, sel, move));
    }

    /** Called by the folder picker once a destination is chosen. */
    void confirmTransfer(final List<Item> todo, final boolean move, final Prefs.Dest dest) {
        long bytes = 0;
        for (Item e : todo) bytes += e.size;
        if (!move) {
            beginTransfer(todo, false, dest);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Move " + todo.size() + " file" + (todo.size() == 1 ? "" : "s") + "?")
                .setMessage(Ui.fmtSize(bytes) + " will go to " + dest.path + ".\n\nEach original is deleted from the card "
                        + "only after its copy has been read back and checked. If anything fails, that original stays on the card.")
                .setPositiveButton("Move", (d, w) -> beginTransfer(todo, true, dest))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView pTitle, pLine1, pLine2, pCancel;
    private ProgressBar pBar;
    private LinearLayout pCard;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final AtomicLong doneBytes = new AtomicLong();
    private volatile int curIndex;
    private volatile String curName = "";
    private long totalBytes, startedAt;
    private volatile boolean deleting = false;
    private int totalFiles;
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!transferring || pBar == null) return;
            long done = doneBytes.get();
            pBar.setProgress(totalBytes > 0 ? (int) (done * 1000 / totalBytes) : 0);
            pLine1.setText(Math.min(curIndex, totalFiles) + " of " + totalFiles + " · " + curName);
            if (deleting) {
                pLine2.setText("Freed " + Ui.fmtSize(done) + " of " + Ui.fmtSize(totalBytes));
                h.postDelayed(this, 150);
                return;
            }
            double secs = (SystemClock.elapsedRealtime() - startedAt) / 1000.0;
            double speed = secs > 0.3 ? done / 1048576.0 / secs : 0;
            String left = speed > 0.1 ? " · about " + fmtDuration((totalBytes - done) / 1048576.0 / speed) + " left" : "";
            pLine2.setText(Ui.fmtSize(done) + " of " + Ui.fmtSize(totalBytes)
                    + (speed > 0 ? String.format(Locale.US, " · %.1f MB/s", speed) : "") + left);
            h.postDelayed(this, 250);
        }
    };

    private static String fmtDuration(double s) {
        if (s < 60) return Math.max(1, Math.round(s)) + " s";
        long m = Math.round(s / 60);
        return m + " min";
    }

    private void beginTransfer(final List<Item> todo, final boolean move, final Prefs.Dest dest) {
        if (transferring) return;
        while (!pages.isEmpty() && pages.peek() instanceof DestPicker) popPage();
        prefs.pushRecent(dest);
        transferring = true;
        thumbs.paused = true;
        cancel.set(false);
        doneBytes.set(0);
        curIndex = 0;
        curName = "";
        totalFiles = todo.size();
        totalBytes = 0;
        for (Item e : todo) totalBytes += e.size;
        startedAt = SystemClock.elapsedRealtime();
        showProgressOverlay(move);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        h.post(progressTick);
        final boolean dated = prefs.dateFolders();
        new Thread(() -> {
            Result r = runTransfer(todo, move, dest, dated);
            h.post(() -> {
                transferring = false;
                thumbs.paused = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                covers.invalidateAll();
                if (!r.moved.isEmpty()) items.removeAll(r.moved);
                for (Item e : r.moved) removedKeys.add(e.key());
                for (Item e : todo) e.selected = false;
                applyFilter();
                showResult(r, move, dest);
            });
        }, "transfer").start();
    }

    private static final class Result {
        int copied, skipped, failed, deleted, deleteFailed;
        long bytes, ms;
        boolean cancelled;
        String fatal;
        final List<Item> moved = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
    }

    private Result runTransfer(List<Item> todo, boolean move, Prefs.Dest dest, boolean dated) {
        Result r = new Result();
        android.content.ContentResolver cr = getContentResolver();
        log((move ? "MOVE " : "COPY ") + todo.size() + " files to " + dest.path + (dated ? " (date folders)" : ""));
        long t0 = SystemClock.elapsedRealtime();
        FileOps.DestDir root;
        try {
            root = FileOps.openDestDir(cr, dest.tree, dest.docId);
        } catch (Exception e) {
            r.fatal = "Can't open the destination folder: " + e.getMessage();
            log(r.fatal);
            return r;
        }
        Map<String, FileOps.DestDir> dirs = new HashMap<>();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        byte[] buf = new byte[1 << 20];
        long finishedBytes = 0;
        int i = 0;
        for (Item e : todo) {
            i++;
            if (cancel.get()) { r.cancelled = true; log("Cancelled after " + (i - 1) + " of " + todo.size()); break; }
            curIndex = i;
            curName = e.name;
            final long base = finishedBytes;
            final long[] partial = {0};
            try {
                FileOps.DestDir d = root;
                if (dated) {
                    String dn = dayFmt.format(new Date(e.mtime));
                    d = dirs.get(dn);
                    if (d == null) { d = FileOps.subDir(cr, root, dn); dirs.put(dn, d); }
                }
                if (d.names.contains(e.name.toLowerCase(Locale.ROOT))) {
                    r.skipped++;
                    log("  skipped (already there): " + e.name);
                } else {
                    long f0 = SystemClock.elapsedRealtime();
                    FileOps.copyAndVerify(cr, e, d, buf, cancel, n -> { partial[0] += n; doneBytes.set(base + partial[0]); }, this::log);
                    long f1 = SystemClock.elapsedRealtime();
                    d.names.add(e.name.toLowerCase(Locale.ROOT));
                    r.copied++;
                    r.bytes += e.size;
                    String line = String.format(Locale.US, "  ok %s (%s, %.1f MB/s incl. verify)", e.name, Ui.fmtSize(e.size),
                            (f1 - f0) > 0 ? (e.size / 1048576.0) / ((f1 - f0) / 1000.0) : 0.0);
                    if (move) {
                        if (FileOps.delete(cr, e.uri)) { r.deleted++; r.moved.add(e); line += " -> original deleted"; }
                        else { r.deleteFailed++; line += " -> could NOT delete original (copy is safe)"; }
                    }
                    log(line);
                }
            } catch (Exception ex) {
                if (cancel.get()) { r.cancelled = true; log("Cancelled during " + e.name + " (partial copy removed)"); break; }
                r.failed++;
                r.failures.add(e.name);
                log("  FAILED " + e.name + ": " + ex.getMessage() + (move ? " (original kept)" : ""));
            }
            finishedBytes += e.size;
            doneBytes.set(finishedBytes);
        }
        r.ms = SystemClock.elapsedRealtime() - t0;
        log(String.format(Locale.US, "DONE: %d copied, %d skipped, %d failed%s. %s in %.1f s = %.1f MB/s",
                r.copied, r.skipped, r.failed, move ? ", " + r.deleted + " originals deleted" : "",
                Ui.fmtSize(r.bytes), r.ms / 1000.0, r.ms > 0 ? (r.bytes / 1048576.0) / (r.ms / 1000.0) : 0.0));
        return r;
    }

    private void showProgressOverlay(boolean move) {
        showProgressOverlay(move ? "Moving photos" : "Copying photos");
    }

    private void showProgressOverlay(String titleText) {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(ui.scrim);
        overlay.setClickable(true);
        pCard = ui.vbox();
        pCard.setBackground(ui.round(ui.surface, 24, 0));
        pCard.setPadding(ui.px(24), ui.px(24), ui.px(24), ui.px(20));
        pCard.setElevation(ui.px(12));
        pTitle = ui.text(titleText, 20, ui.text, true);
        pCard.addView(pTitle);
        pBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pBar.setMax(1000);
        pBar.setProgressTintList(ColorStateList.valueOf(ui.accent));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, ui.px(12));
        bp.setMargins(0, ui.px(20), 0, ui.px(12));
        pCard.addView(pBar, bp);
        pLine1 = ui.text("Starting…", 15, ui.text, false);
        pLine1.setSingleLine(true);
        pLine1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pCard.addView(pLine1);
        pLine2 = ui.text("", 13, ui.textDim, false);
        pLine2.setPadding(0, ui.px(4), 0, 0);
        pCard.addView(pLine2);
        pCancel = ui.secondaryButton("Cancel", v -> {
            cancel.set(true);
            pCancel.setText("Cancelling…");
            pCancel.setEnabled(false);
        });
        LinearLayout.LayoutParams cp = Ui.lp(Ui.MATCH, Ui.WRAP);
        cp.setMargins(0, ui.px(20), 0, 0);
        pCard.addView(pCancel, cp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER);
        lp.setMargins(ui.px(24), 0, ui.px(24), 0);
        overlay.addView(pCard, lp);
        host.addView(overlay, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private void showResult(Result r, boolean move, Prefs.Dest dest) {
        if (overlay == null) return;
        h.removeCallbacks(progressTick);
        pCard.removeAllViews();
        boolean problems = r.failed > 0 || r.deleteFailed > 0 || r.fatal != null;
        String head = r.fatal != null ? "Couldn't start" : r.cancelled ? "Stopped" : problems ? "Finished with problems" : "Done";
        pCard.addView(ui.text(head, 22, problems ? ui.danger : ui.text, true));
        StringBuilder sb = new StringBuilder();
        if (r.fatal != null) {
            sb.append(r.fatal);
        } else {
            sb.append(r.copied).append(r.copied == 1 ? " file " : " files ").append(move ? "moved" : "copied")
                    .append(" to ").append(dest.path).append(".");
            if (r.copied > 0) sb.append(String.format(Locale.US, "\n%s in %.1f s", Ui.fmtSize(r.bytes), r.ms / 1000.0));
            if (r.skipped > 0) sb.append("\n").append(r.skipped).append(" skipped: a file with the same name was already there.");
            if (r.failed > 0) sb.append("\n").append(r.failed).append(" failed").append(move ? " (originals kept on the card)" : "")
                    .append(": ").append(join(r.failures, 4)).append(".");
            if (r.deleteFailed > 0) sb.append("\n").append(r.deleteFailed).append(" copied but the original could not be deleted.");
            if (r.cancelled) sb.append("\nYou cancelled before the rest were done.");
        }
        TextView body = ui.text(sb.toString(), 15, ui.textDim, false);
        body.setPadding(0, ui.px(12), 0, 0);
        body.setLineSpacing(0, 1.15f);
        pCard.addView(body);
        LinearLayout btns = ui.hbox();
        btns.setGravity(Gravity.END);
        if (problems) btns.addView(ui.flatButton("Show log", v -> { closeOverlay(); pushPage(new LogPage()); }));
        if (r.fatal == null && (r.copied > 0 || r.skipped > 0)) {
            btns.addView(ui.flatButton("Show in album", v -> { closeOverlay(); pushPage(DestPicker.browse(this, dest)); }));
        }
        btns.addView(ui.primaryButton("Done", v -> closeOverlay()));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, Ui.WRAP);
        bp.setMargins(0, ui.px(20), 0, 0);
        pCard.addView(btns, bp);
    }

    private static String join(List<String> names, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < max; i++) { if (i > 0) sb.append(", "); sb.append(names.get(i)); }
        if (names.size() > max) sb.append(" and ").append(names.size() - max).append(" more");
        return sb.toString();
    }

    // ================================================================== delete

    /** The flat trash-can vector icon, tinted and sized (dp). */
    android.graphics.drawable.Drawable trashIcon(int color, int sizeDp) {
        int id = getResources().getIdentifier("ic_trash", "drawable", getPackageName());
        if (id == 0) return null;
        android.graphics.drawable.Drawable d = getDrawable(id);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(0, 0, ui.px(sizeDp), ui.px(sizeDp));
        return d;
    }

    /** Asks before permanently deleting files from the card. */
    void confirmDelete(final List<Item> todo) {
        if (todo.isEmpty() || transferring) return;
        long bytes = 0;
        int raw = 0, jpg = 0, other = 0;
        for (Item e : todo) {
            bytes += e.size;
            if (e.type == Item.T_RAW) raw++; else if (e.type == Item.T_JPG) jpg++; else other++;
        }
        StringBuilder what = new StringBuilder();
        if (raw > 0) what.append(raw).append(" RAW");
        if (jpg > 0) what.append(what.length() > 0 ? ", " : "").append(jpg).append(" JPEG");
        if (other > 0) what.append(what.length() > 0 ? ", " : "").append(other).append(" other");
        int n = todo.size();
        String one = n == 1 ? todo.get(0).name : null;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(one != null ? "Delete " + one + "?" : "Delete " + n + " files?")
                .setMessage((one != null ? "" : what + " · ") + Ui.fmtSize(bytes) + "\n\nThis permanently deletes "
                        + (n == 1 ? "it" : "them") + " from the SD card. It can't be undone.")
                .setPositiveButton("Delete", (dd, w) -> beginDelete(todo))
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.danger);
    }

    /** The other half of a RAW+JPEG pair: same name, same folder, the other type. */
    Item partnerOf(Item e) {
        if (e.type == Item.T_OTHER) return null;
        String base = baseName(e.name), dir = parentId(e);
        for (Item o : items) {
            if (o == e || o.type == Item.T_OTHER || o.type == e.type) continue;
            if (baseName(o.name).equals(base) && parentId(o).equals(dir)) return o;
        }
        return null;
    }

    private static String baseName(String n) {
        int i = n.lastIndexOf('.');
        return (i > 0 ? n.substring(0, i) : n).toLowerCase(Locale.ROOT);
    }

    private static String parentId(Item e) {
        String id = android.provider.DocumentsContract.getDocumentId(e.uri);
        int i = id.lastIndexOf('/');
        return i >= 0 ? id.substring(0, i) : "";
    }

    /** Delete one photo (from the viewer), offering to delete its RAW/JPEG partner too (ticked by default). */
    void confirmDeleteOne(final Item e) {
        if (transferring) return;
        final Item partner = partnerOf(e);
        LinearLayout box = ui.vbox();
        box.setPadding(ui.px(24), ui.px(8), ui.px(24), 0);
        TextView msg = ui.text(Item.TYPE_NAMES[e.type] + " \u00B7 " + Ui.fmtSize(e.size)
                + "\n\nThis permanently deletes it from the SD card. It can't be undone.", 15, ui.textDim, false);
        box.addView(msg);
        final android.widget.CheckBox also = new android.widget.CheckBox(this);
        if (partner != null) {
            also.setText("Also delete the matching " + (partner.type == Item.T_RAW ? "RAW" : "JPEG") + ": "
                    + partner.name + " (" + Ui.fmtSize(partner.size) + ")");
            also.setTextColor(ui.text);
            also.setChecked(true);
            LinearLayout.LayoutParams cp = Ui.lp(Ui.MATCH, Ui.WRAP);
            cp.setMargins(0, ui.px(12), 0, 0);
            box.addView(also, cp);
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Delete " + e.name + "?")
                .setView(box)
                .setPositiveButton("Delete", (dd, w) -> {
                    List<Item> todo = new ArrayList<>();
                    todo.add(e);
                    if (partner != null && also.isChecked()) todo.add(partner);
                    beginDelete(todo);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.danger);
    }

    private void beginDelete(final List<Item> todo) {
        if (transferring) return;
        transferring = true;
        deleting = true;
        thumbs.paused = true;
        cancel.set(false);
        doneBytes.set(0);
        curIndex = 0;
        curName = "";
        totalFiles = todo.size();
        totalBytes = 0;
        for (Item e : todo) totalBytes += e.size;
        startedAt = SystemClock.elapsedRealtime();
        showProgressOverlay("Deleting from the card");
        h.post(progressTick);
        new Thread(() -> {
            android.content.ContentResolver cr = getContentResolver();
            final List<Item> gone = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            boolean cancelled = false;
            long freed = 0;
            log("DELETE " + todo.size() + " files from the card");
            int i = 0;
            for (Item e : todo) {
                if (cancel.get()) { cancelled = true; break; }
                curIndex = ++i;
                curName = e.name;
                if (FileOps.delete(cr, e.uri)) { gone.add(e); freed += e.size; log("  deleted " + e.name); }
                else { failed.add(e.name); log("  could NOT delete " + e.name); }
                doneBytes.set(freed);
            }
            final boolean fc = cancelled;
            final long ff = freed;
            log("DONE: " + gone.size() + " deleted, " + failed.size() + " failed" + (fc ? " (cancelled)" : ""));
            h.post(() -> {
                transferring = false;
                deleting = false;
                thumbs.paused = false;
                items.removeAll(gone);
                for (Item e : gone) removedKeys.add(e.key());
                for (Item e : todo) e.selected = false;
                applyFilter();
                refreshPages();
                showDeleteResult(gone.size(), failed, ff, fc);
            });
        }, "delete").start();
    }

    private void showDeleteResult(int deleted, List<String> failed, long freed, boolean cancelled) {
        if (overlay == null) return;
        h.removeCallbacks(progressTick);
        boolean problems = !failed.isEmpty();
        if (!problems && !cancelled && deleted <= 3) {  // small deletes: no need for a summary screen
            closeOverlay();
            Toast.makeText(this, deleted == 1 ? "Deleted" : "Deleted " + deleted + " files", Toast.LENGTH_SHORT).show();
            return;
        }
        pCard.removeAllViews();
        pCard.addView(ui.text(problems ? "Finished with problems" : cancelled ? "Stopped" : "Deleted",
                22, problems ? ui.danger : ui.text, true));
        StringBuilder sb = new StringBuilder();
        sb.append(deleted).append(deleted == 1 ? " file" : " files").append(" deleted from the card (")
                .append(Ui.fmtSize(freed)).append(" freed).");
        if (problems) sb.append("\n").append(failed.size()).append(" could not be deleted: ").append(join(failed, 4)).append(".");
        if (cancelled) sb.append("\nYou cancelled before the rest were deleted.");
        TextView body = ui.text(sb.toString(), 15, ui.textDim, false);
        body.setPadding(0, ui.px(12), 0, 0);
        pCard.addView(body);
        LinearLayout btns = ui.hbox();
        btns.setGravity(Gravity.END);
        if (problems) btns.addView(ui.flatButton("Show log", v -> { closeOverlay(); pushPage(new LogPage()); }));
        btns.addView(ui.primaryButton("Done", v -> closeOverlay()));
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, Ui.WRAP);
        bp.setMargins(0, ui.px(20), 0, 0);
        pCard.addView(btns, bp);
    }

    private void closeOverlay() {
        if (overlay != null) host.removeView(overlay);
        overlay = null;
        pBar = null;
    }

    // ================================================================== settings page

    private final class SettingsPage implements Page {
        private final LinearLayout root = ui.vbox();
        private final LinearLayout body = ui.vbox();

        SettingsPage() {
            root.setBackgroundColor(ui.bg);
            root.addView(pageBar("Settings", null), Ui.lp(Ui.MATCH, Ui.WRAP));
            ScrollView sv = new ScrollView(MainActivity.this);
            sv.addView(body, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
            refresh();
        }

        @Override public View view() { return root; }

        private LinearLayout card() {
            LinearLayout c = ui.vbox();
            c.setBackground(ui.round(ui.surface, 16, 0));
            LinearLayout.LayoutParams p = Ui.lp(Ui.MATCH, Ui.WRAP);
            p.setMargins(ui.px(12), 0, ui.px(12), 0);
            c.setLayoutParams(p);
            return c;
        }

        @Override
        public void refresh() {
            body.removeAllViews();

            body.addView(ui.sectionHeader("Camera card"));
            LinearLayout c1 = card();
            Uri cardUri = prefs.card();
            c1.addView(ui.listRow("💾", "Card folder", cardUri == null ? "Not set - tap to choose" : Prefs.rootLabel(cardUri),
                    null, v -> pickCard()));
            c1.addView(ui.divider());
            c1.addView(ui.listRow("⟳", "Rescan card", scanning ? "Reading…" : items.size() + " files found",
                    null, v -> startScan()));
            body.addView(c1);

            body.addView(ui.sectionHeader("Destination folders"));
            TextView expl = ui.text("Add a top folder once (for example Pictures or DCIM on the phone). When you copy, you "
                    + "pick or create sub-folders inside it with the app's own folder browser.", 13, ui.textDim, false);
            expl.setPadding(ui.px(16), 0, ui.px(16), ui.px(8));
            body.addView(expl);
            LinearLayout c2 = card();
            List<Uri> roots = prefs.roots();
            for (Uri u : roots) {
                TextView remove = ui.flatButton("Remove", v -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Remove " + Prefs.rootLabel(u) + "?")
                        .setMessage("The app will no longer be able to save into this folder. Files already there are not touched.")
                        .setPositiveButton("Remove", (d, w) -> { prefs.removeRoot(u); refresh(); })
                        .setNegativeButton("Cancel", null).show());
                c2.addView(ui.listRow("📁", Prefs.rootLabel(u), null, remove, null));
                c2.addView(ui.divider());
            }
            c2.addView(ui.listRow("＋", "Add destination folder", "Uses Android's folder picker, just this once", null, v -> pickRoot()));
            if (!prefs.recents().isEmpty()) {
                c2.addView(ui.divider());
                c2.addView(ui.listRow("🕘", "Clear recent destinations", null, null, v -> { prefs.clearRecents(); refresh(); }));
            }
            body.addView(c2);

            body.addView(ui.sectionHeader("Copying"));
            LinearLayout c3 = card();
            Switch sw = new Switch(MainActivity.this);
            sw.setChecked(prefs.dateFolders());
            sw.setOnCheckedChangeListener((v, on) -> prefs.setDateFolders(on));
            c3.addView(ui.listRow("📅", "Sort into date folders", "Creates YYYY-MM-DD folders from the card's file dates",
                    sw, v -> sw.toggle()));
            c3.addView(ui.divider());
            c3.addView(ui.listRow("✔", "Every copy is verified", "Files already at the destination are skipped, never "
                    + "overwritten. Move deletes an original only after its copy checks out.", null, null));
            body.addView(c3);

            body.addView(ui.sectionHeader("Gallery"));
            LinearLayout c4 = card();
            c4.addView(ui.listRow("▦", "Grid columns", prefs.columns() + "  (you can also pinch the gallery)", null, v -> {
                final String[] opts = {"2", "3", "4", "5", "6", "7"};
                new AlertDialog.Builder(MainActivity.this).setTitle("Grid columns")
                        .setSingleChoiceItems(opts, prefs.columns() - 2, (d, which) -> {
                            prefs.setColumns(which + 2);
                            gridCols = -1;
                            applyFilter();
                            refresh();
                            d.dismiss();
                        }).show();
            }));
            body.addView(c4);

            body.addView(ui.sectionHeader("Diagnostics"));
            LinearLayout c5 = card();
            c5.addView(ui.listRow("📝", "Activity log", "Details of scans and transfers, and a copyable report",
                    null, v -> pushPage(new LogPage())));
            c5.addView(ui.divider());
            c5.addView(ui.listRow("🖼", "Thumbnail stats", thumbs.stats(), null, v -> refresh()));
            body.addView(c5);

            TextView about = ui.text("Offload " + versionName(), 13, ui.textDim, false);
            about.setGravity(Gravity.CENTER);
            about.setPadding(0, ui.px(24), 0, ui.px(32));
            body.addView(about, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
    }

    // ================================================================== log page

    private View logPageView;

    private final class LogPage implements Page {
        private final LinearLayout root = ui.vbox();

        LogPage() {
            root.setBackgroundColor(ui.bg);
            root.addView(pageBar("Activity log", null, ui.flatButton("Copy", v -> copyReport())), Ui.lp(Ui.MATCH, Ui.WRAP));
            ScrollView sv = new ScrollView(MainActivity.this);
            TextView tv = ui.text("", 12, ui.text, false);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(ui.px(12), ui.px(12), ui.px(12), ui.px(24));
            synchronized (report) { tv.setText(report.toString()); }
            sv.addView(tv);
            root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
            logView = tv;
            logPageView = root;
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }

        @Override public View view() { return root; }
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String text;
        synchronized (report) { text = report.toString(); }
        cm.setPrimaryClip(ClipData.newPlainText("Offload report", text));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    void log(final String msg) {
        final String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n";
        synchronized (report) { report.append(line); }
        h.post(() -> { if (logView != null) logView.append(line); });
    }
}
