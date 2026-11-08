package com.ttblood98.sdcardmanager.speedtest;

import android.app.AlertDialog;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The app's own destination folder browser. It works inside folders the user granted once (Settings), so it can
 * list and create sub-folders freely, show recent destinations, and avoid Android's generic picker for every copy.
 */
final class DestPicker implements MainActivity.Page {
    private final MainActivity a;
    private final Ui ui;
    private final List<Item> todo;
    private final boolean move;
    /** Path from a granted root down to the current folder; empty = the start screen. */
    private final List<Prefs.Dest> path = new ArrayList<>();
    private final LinearLayout root;
    private final LinearLayout body;
    private final LinearLayout crumbs;
    private final HorizontalScrollView crumbScroll;
    private final LinearLayout bottom;
    private final TextView goBtn;
    private int token = 0;

    DestPicker(MainActivity a, List<Item> todo, boolean move) {
        this.a = a;
        this.ui = a.ui;
        this.todo = todo;
        this.move = move;
        long bytes = 0;
        for (Item e : todo) bytes += e.size;

        root = ui.vbox();
        root.setBackgroundColor(ui.bg);
        TextView sub = ui.text(todo.size() + " file" + (todo.size() == 1 ? "" : "s") + " · " + Ui.fmtSize(bytes), 13, ui.textDim, false);
        root.addView(a.pageBar(move ? "Move to…" : "Copy to…", sub), Ui.lp(Ui.MATCH, Ui.WRAP));

        crumbScroll = new HorizontalScrollView(a);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.setBackgroundColor(ui.surface);
        crumbs = ui.hbox();
        crumbs.setPadding(ui.px(8), ui.px(4), ui.px(8), ui.px(4));
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll, Ui.lp(Ui.MATCH, Ui.WRAP));

        ScrollView sv = new ScrollView(a);
        body = ui.vbox();
        body.setPadding(0, 0, 0, ui.px(16));
        sv.addView(body, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        bottom = ui.hbox();
        bottom.setBackgroundColor(ui.surface);
        bottom.setElevation(ui.px(8));
        bottom.setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10));
        TextView newFolder = ui.secondaryButton("New folder", v -> askNewFolder());
        bottom.addView(newFolder, Ui.weight(1));
        View gap = new View(a);
        bottom.addView(gap, Ui.lp(ui.px(10), 1));
        goBtn = ui.primaryButton(move ? "Move here" : "Copy here", v -> go());
        bottom.addView(goBtn, Ui.weight(1.3f));
        root.addView(bottom, Ui.lp(Ui.MATCH, Ui.WRAP));
        refresh();
    }

    @Override public View view() { return root; }

    @Override
    public boolean onBack() {
        if (path.isEmpty()) return false;
        path.remove(path.size() - 1);
        refresh();
        return true;
    }

    private Prefs.Dest current() { return path.isEmpty() ? null : path.get(path.size() - 1); }

    @Override
    public void refresh() {
        token++;
        buildCrumbs();
        body.removeAllViews();
        Prefs.Dest cur = current();
        bottom.setVisibility(cur == null ? View.GONE : View.VISIBLE);
        if (cur == null) showStart(); else showFolder(cur);
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        crumbs.addView(crumb("All folders", path.isEmpty(), v -> { path.clear(); refresh(); }));
        for (int i = 0; i < path.size(); i++) {
            final int depth = i;
            TextView sep = ui.text("›", 16, ui.textDim, false);
            sep.setPadding(ui.px(2), 0, ui.px(2), 0);
            crumbs.addView(sep);
            String name = i == 0 ? path.get(0).path : lastSegment(path.get(i).path);
            crumbs.addView(crumb(name, i == path.size() - 1, v -> {
                while (path.size() > depth + 1) path.remove(path.size() - 1);
                refresh();
            }));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private TextView crumb(String s, boolean last, View.OnClickListener l) {
        TextView t = ui.text(s, 14, last ? ui.text : ui.accent, last);
        t.setPadding(ui.px(8), ui.px(10), ui.px(8), ui.px(10));
        t.setBackground(ui.ripple(null, ui.rippleColor()));
        t.setOnClickListener(l);
        return t;
    }

    private static String lastSegment(String p) {
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private LinearLayout card() {
        LinearLayout c = ui.vbox();
        c.setBackground(ui.round(ui.surface, 16, 0));
        LinearLayout.LayoutParams p = Ui.lp(Ui.MATCH, Ui.WRAP);
        p.setMargins(ui.px(12), 0, ui.px(12), 0);
        c.setLayoutParams(p);
        return c;
    }

    private void showStart() {
        List<Prefs.Dest> recents = a.prefs.recents();
        List<Uri> roots = a.prefs.roots();
        if (!recents.isEmpty()) {
            body.addView(ui.sectionHeader("Recent"));
            LinearLayout c = card();
            boolean first = true;
            for (Prefs.Dest d : recents) {
                if (!first) c.addView(ui.divider());
                first = false;
                c.addView(ui.listRow("🕘", lastSegment(d.path), d.path, null, v -> openRecent(d)));
            }
            body.addView(c);
        }
        body.addView(ui.sectionHeader("Folders"));
        LinearLayout c2 = card();
        if (roots.isEmpty()) {
            TextView t = ui.text("No destination folders yet. Add a top folder once, for example Pictures or DCIM "
                    + "on the phone. After that you can browse and create folders inside it right here.", 14, ui.textDim, false);
            t.setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(6));
            c2.addView(t);
        }
        for (Uri u : roots) {
            c2.addView(ui.listRow("📁", Prefs.rootLabel(u), null, chevron(), v -> {
                Prefs.Dest d = new Prefs.Dest();
                d.tree = u;
                d.docId = DocumentsContract.getTreeDocumentId(u);
                d.path = Prefs.rootLabel(u);
                path.clear();
                path.add(d);
                refresh();
            }));
            c2.addView(ui.divider());
        }
        c2.addView(ui.listRow("＋", "Add a folder from phone storage", "Android's picker opens once; the folder is then remembered",
                null, v -> a.pickRoot()));
        body.addView(c2);
    }

    private TextView chevron() {
        TextView t = ui.text("›", 22, ui.textDim, false);
        t.setPadding(ui.px(8), 0, ui.px(4), 0);
        return t;
    }

    private void openRecent(Prefs.Dest d) {
        path.clear();
        // Show the root first so Back and the breadcrumbs make sense, then the recent folder itself.
        Prefs.Dest rootDest = new Prefs.Dest();
        rootDest.tree = d.tree;
        rootDest.docId = DocumentsContract.getTreeDocumentId(d.tree);
        rootDest.path = Prefs.rootLabel(d.tree);
        path.add(rootDest);
        if (!rootDest.docId.equals(d.docId)) path.add(d);
        refresh();
    }

    private void showFolder(Prefs.Dest cur) {
        TextView loading = ui.text("Loading folders…", 14, ui.textDim, false);
        loading.setPadding(ui.px(16), ui.px(20), ui.px(16), ui.px(8));
        body.addView(loading);
        final int my = token;
        a.bg.execute(() -> {
            List<String[]> folders;
            String err = null;
            try {
                folders = FileOps.listFolders(a.getContentResolver(), cur.tree, cur.docId);
            } catch (Exception e) {
                folders = new ArrayList<>();
                err = e.getMessage();
            }
            final List<String[]> f = folders;
            final String fe = err;
            a.h.post(() -> {
                if (my != token) return;
                body.removeAllViews();
                TextView here = ui.text((move ? "Files will be moved into " : "Files will be copied into ") + lastSegment(cur.path)
                        + (a.prefs.dateFolders() ? ", in date folders" : ""), 13, ui.textDim, false);
                here.setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(6));
                body.addView(here);
                if (fe != null) {
                    TextView t = ui.text("Couldn't open this folder: " + fe, 14, ui.danger, false);
                    t.setPadding(ui.px(16), ui.px(8), ui.px(16), ui.px(8));
                    body.addView(t);
                    return;
                }
                body.addView(ui.sectionHeader(f.isEmpty() ? "No folders inside" : "Folders inside"));
                if (f.isEmpty()) {
                    TextView t = ui.text("Tap “" + (move ? "Move here" : "Copy here") + "” to use this folder, or "
                            + "make a new folder.", 14, ui.textDim, false);
                    t.setPadding(ui.px(16), 0, ui.px(16), ui.px(8));
                    body.addView(t);
                    return;
                }
                LinearLayout c = card();
                boolean first = true;
                for (String[] folder : f) {
                    if (!first) c.addView(ui.divider());
                    first = false;
                    c.addView(ui.listRow("📁", folder[1], null, chevron(), v -> {
                        Prefs.Dest d = new Prefs.Dest();
                        d.tree = cur.tree;
                        d.docId = folder[0];
                        d.path = cur.path + "/" + folder[1];
                        path.add(d);
                        refresh();
                    }));
                }
                body.addView(c);
            });
        });
    }

    private void askNewFolder() {
        final Prefs.Dest cur = current();
        if (cur == null) return;
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        et.setText(today);
        et.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(ui.px(20), ui.px(8), ui.px(20), 0);
        wrap.addView(et);
        new AlertDialog.Builder(a)
                .setTitle("New folder in " + lastSegment(cur.path))
                .setView(wrap)
                .setPositiveButton("Create", (d, w) -> {
                    final String name = et.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (name.isEmpty()) return;
                    a.bg.execute(() -> {
                        try {
                            String id = FileOps.createFolder(a.getContentResolver(), cur.tree, cur.docId, name);
                            a.h.post(() -> {
                                Prefs.Dest nd = new Prefs.Dest();
                                nd.tree = cur.tree;
                                nd.docId = id;
                                nd.path = cur.path + "/" + name;
                                path.add(nd);
                                refresh();
                            });
                        } catch (Exception e) {
                            a.log("Could not create folder " + name + ": " + e.getMessage());
                            a.h.post(() -> new AlertDialog.Builder(a).setTitle("Couldn't create the folder")
                                    .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
        et.requestFocus();
    }

    private void go() {
        Prefs.Dest cur = current();
        if (cur == null) return;
        a.confirmTransfer(todo, move, cur);
    }
}
