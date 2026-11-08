package com.ttblood98.sdcardmanager.speedtest;

import android.app.AlertDialog;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Where to put the photos: a second gallery, of albums. Folders are shown as album tiles with a cover photo and a
 * count. Opening an album shows the albums inside it and a preview of the photos already there, plus a big
 * "Copy here" button. It works inside the top folders granted once in Settings.
 */
final class DestPicker implements MainActivity.Page {
    private static final int ALBUM_COLS = 3, PHOTO_COLS = 4, PHOTO_PREVIEW = 48;

    private final MainActivity a;
    private final Ui ui;
    private final List<Item> todo;
    private final boolean move;
    /** Path from a granted location down to the open album; empty = the start screen. */
    private final List<Prefs.Dest> path = new ArrayList<>();
    private final LinearLayout root, body, crumbs, bottom;
    private final HorizontalScrollView crumbScroll;
    private final ScrollView scroll;
    private final TextView goBtn;
    private int token = 0;

    DestPicker(MainActivity a, List<Item> todo, boolean move) {
        this.a = a;
        this.ui = a.ui;
        this.todo = todo;
        this.move = move;
        long bytes = 0;
        for (Item e : todo) bytes += e.size;
        String what = todo.size() + " photo" + (todo.size() == 1 ? "" : "s");

        root = ui.vbox();
        root.setBackgroundColor(ui.bg);
        TextView sub = ui.text(what + " · " + Ui.fmtSize(bytes), 13, ui.textDim, false);
        root.addView(a.pageBar(move ? "Move to album" : "Copy to album", sub), Ui.lp(Ui.MATCH, Ui.WRAP));

        crumbScroll = new HorizontalScrollView(a);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.setBackgroundColor(ui.surface);
        crumbs = ui.hbox();
        crumbs.setPadding(ui.px(8), 0, ui.px(8), 0);
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll, Ui.lp(Ui.MATCH, Ui.WRAP));

        scroll = new ScrollView(a);
        body = ui.vbox();
        body.setPadding(ui.px(12), 0, ui.px(12), ui.px(24));
        scroll.addView(body, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        bottom = ui.hbox();
        bottom.setBackgroundColor(ui.surface);
        bottom.setElevation(ui.px(8));
        bottom.setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10));
        goBtn = ui.primaryButton((move ? "Move " : "Copy ") + what + " here", v -> go());
        bottom.addView(goBtn, Ui.weight(1));
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
        body.removeAllViews();
        scroll.scrollTo(0, 0);
        buildCrumbs();
        Prefs.Dest cur = current();
        crumbScroll.setVisibility(cur == null ? View.GONE : View.VISIBLE);
        bottom.setVisibility(cur == null ? View.GONE : View.VISIBLE);
        if (cur == null) showStart(); else showAlbum(cur);
    }

    // ---------------------------------------------------------------- breadcrumbs

    private void buildCrumbs() {
        crumbs.removeAllViews();
        crumbs.addView(crumb("Albums", false, v -> { path.clear(); refresh(); }));
        for (int i = 0; i < path.size(); i++) {
            final int depth = i;
            TextView sep = ui.text("›", 16, ui.textDim, false);
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
        t.setPadding(ui.px(8), ui.px(12), ui.px(8), ui.px(12));
        t.setBackground(ui.ripple(null, ui.rippleColor()));
        t.setOnClickListener(l);
        return t;
    }

    private static String lastSegment(String p) {
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    // ---------------------------------------------------------------- tiles

    private TextView section(String title) {
        TextView t = ui.text(title, 17, ui.text, true);
        t.setPadding(ui.px(4), ui.px(20), ui.px(4), ui.px(10));
        return t;
    }

    /** An album tile: rounded cover photo, name, and "N photos". */
    private View albumTile(Uri tree, String docId, String name, View.OnClickListener l) {
        LinearLayout tile = ui.vbox();
        Ui.Square cover = new Ui.Square(a);
        cover.setBackground(ui.round(ui.surface2, 14, 0));
        cover.setClipToOutline(true);
        TextView glyph = ui.text("📁", 28, ui.textDim, false);
        glyph.setGravity(Gravity.CENTER);
        cover.addView(glyph, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        cover.setForeground(ui.ripple(null, 0x33FFFFFF));
        cover.setOnClickListener(l);
        tile.addView(cover, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView nm = ui.text(name, 14, ui.text, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setPadding(ui.px(2), ui.px(6), ui.px(2), 0);
        tile.addView(nm);
        TextView count = ui.text(" ", 12, ui.textDim, false);
        count.setSingleLine(true);
        count.setPadding(ui.px(2), 0, ui.px(2), 0);
        tile.addView(count);
        a.covers.bind(iv, count, tree, docId);
        return tile;
    }

    private View newAlbumTile(final Prefs.Dest parent) {
        LinearLayout tile = ui.vbox();
        Ui.Square box = new Ui.Square(a);
        box.setBackground(ui.ripple(ui.round(0x00000000, 14, ui.divider), ui.rippleColor()));
        TextView plus = ui.text("＋", 30, ui.accent, false);
        plus.setGravity(Gravity.CENTER);
        box.addView(plus, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        box.setOnClickListener(v -> askNewAlbum(parent));
        tile.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView nm = ui.text("New album", 14, ui.accent, true);
        nm.setPadding(ui.px(2), ui.px(6), ui.px(2), 0);
        tile.addView(nm);
        return tile;
    }

    private Prefs.Dest child(Prefs.Dest parent, String docId, String name) {
        Prefs.Dest d = new Prefs.Dest();
        d.tree = parent.tree;
        d.docId = docId;
        d.path = parent.path + "/" + name;
        return d;
    }

    private static Prefs.Dest location(Uri u) {
        Prefs.Dest d = new Prefs.Dest();
        d.tree = u;
        d.docId = DocumentsContract.getTreeDocumentId(u);
        d.path = Prefs.rootLabel(u);
        return d;
    }

    // ---------------------------------------------------------------- start screen: recent + every location's albums

    private void showStart() {
        List<Prefs.Dest> recents = a.prefs.recents();
        List<Uri> roots = a.prefs.roots();
        if (!recents.isEmpty()) {
            body.addView(section("Recent"));
            List<View> tiles = new ArrayList<>();
            for (Prefs.Dest d : recents) tiles.add(albumTile(d.tree, d.docId, lastSegment(d.path), v -> openRecent(d)));
            body.addView(ui.grid(tiles, ALBUM_COLS, 10));
        }
        if (roots.isEmpty()) {
            body.addView(section("Albums"));
            TextView t = ui.text("To start, add a place on the phone to keep photos, such as the Pictures or DCIM folder. "
                    + "Android asks once; after that, your albums show up here.", 15, ui.textDim, false);
            t.setPadding(ui.px(4), 0, ui.px(4), ui.px(12));
            body.addView(t);
            body.addView(ui.primaryButton("Add a photo location", v -> a.pickRoot()), Ui.lp(Ui.MATCH, Ui.WRAP));
            return;
        }
        final int my = token;
        for (Uri u : roots) {
            final Prefs.Dest loc = location(u);
            LinearLayout head = ui.hbox();
            TextView ht = section(loc.path);
            head.addView(ht, Ui.weight(1));
            head.addView(ui.flatButton("Open ›", v -> { path.clear(); path.add(loc); refresh(); }));
            body.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
            final LinearLayout slot = ui.vbox();
            body.addView(slot, Ui.lp(Ui.MATCH, Ui.WRAP));
            a.bg.execute(() -> {
                List<String[]> folders;
                try { folders = FileOps.listFolders(a.getContentResolver(), loc.tree, loc.docId); }
                catch (Exception e) { folders = new ArrayList<>(); }
                final List<String[]> f = folders;
                a.h.post(() -> {
                    if (my != token) return;
                    List<View> tiles = new ArrayList<>();
                    tiles.add(newAlbumTile(loc));
                    for (String[] fo : f) {
                        Prefs.Dest d = child(loc, fo[0], fo[1]);
                        tiles.add(albumTile(d.tree, d.docId, fo[1], v -> { path.clear(); path.add(loc); path.add(d); refresh(); }));
                    }
                    slot.addView(ui.grid(tiles, ALBUM_COLS, 10));
                });
            });
        }
        TextView add = ui.flatButton("＋  Add another photo location", v -> a.pickRoot());
        add.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap = Ui.lp(Ui.MATCH, Ui.WRAP);
        ap.setMargins(0, ui.px(20), 0, 0);
        body.addView(add, ap);
    }

    private void openRecent(Prefs.Dest d) {
        path.clear();
        Prefs.Dest loc = location(d.tree);
        path.add(loc);
        if (!loc.docId.equals(d.docId)) path.add(d);
        refresh();
    }

    // ---------------------------------------------------------------- inside an album

    private void showAlbum(final Prefs.Dest cur) {
        TextView name = ui.text(lastSegment(cur.path), 24, ui.text, true);
        name.setPadding(ui.px(4), ui.px(18), ui.px(4), 0);
        body.addView(name);
        final TextView meta = ui.text("Loading…", 14, ui.textDim, false);
        meta.setPadding(ui.px(4), ui.px(2), ui.px(4), 0);
        body.addView(meta);
        if (a.prefs.dateFolders()) {
            TextView note = ui.text("Photos will go into date folders (YYYY-MM-DD) inside this album.", 13, ui.textDim, false);
            note.setPadding(ui.px(4), ui.px(6), ui.px(4), 0);
            body.addView(note);
        }
        final LinearLayout slot = ui.vbox();
        body.addView(slot, Ui.lp(Ui.MATCH, Ui.WRAP));
        final int my = token;
        a.bg.execute(() -> {
            FileOps.Children ch;
            String err = null;
            try { ch = FileOps.listChildren(a.getContentResolver(), cur.tree, cur.docId); }
            catch (Exception e) { ch = new FileOps.Children(); err = e.getMessage(); }
            final FileOps.Children fch = ch;
            final String fe = err;
            a.h.post(() -> {
                if (my != token) return;
                if (fe != null) { meta.setText("Couldn't open this album: " + fe); return; }
                meta.setText(Covers.describe(fch));

                slot.addView(section(fch.folders.isEmpty() ? "Albums" : "Albums inside"));
                List<View> tiles = new ArrayList<>();
                tiles.add(newAlbumTile(cur));
                for (String[] fo : fch.folders) {
                    Prefs.Dest d = child(cur, fo[0], fo[1]);
                    tiles.add(albumTile(d.tree, d.docId, fo[1], v -> { path.add(d); refresh(); }));
                }
                slot.addView(ui.grid(tiles, ALBUM_COLS, 10));

                slot.addView(section("Photos in this album"));
                if (fch.photos.isEmpty()) {
                    TextView t = ui.text("No photos here yet.", 14, ui.textDim, false);
                    t.setPadding(ui.px(4), 0, ui.px(4), 0);
                    slot.addView(t);
                    return;
                }
                List<View> ph = new ArrayList<>();
                int n = Math.min(PHOTO_PREVIEW, fch.photos.size());
                for (int i = 0; i < n; i++) {
                    Ui.Square sq = new Ui.Square(a);
                    sq.setBackgroundColor(ui.surface2);
                    ImageView iv = new ImageView(a);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    sq.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                    a.thumbs.bind(iv, fch.photos.get(i));
                    ph.add(sq);
                }
                slot.addView(ui.grid(ph, PHOTO_COLS, 3));
                if (fch.photos.size() > n) {
                    TextView more = ui.text("and " + (fch.photos.size() - n) + " more", 13, ui.textDim, false);
                    more.setPadding(ui.px(4), ui.px(8), ui.px(4), 0);
                    slot.addView(more);
                }
            });
        });
    }

    // ---------------------------------------------------------------- actions

    private void askNewAlbum(final Prefs.Dest parent) {
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        et.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(ui.px(20), ui.px(8), ui.px(20), 0);
        wrap.addView(et);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("New album in " + lastSegment(parent.path))
                .setView(wrap)
                .setPositiveButton("Create", (d, w) -> {
                    final String name = et.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (name.isEmpty()) return;
                    a.bg.execute(() -> {
                        try {
                            String id = FileOps.createFolder(a.getContentResolver(), parent.tree, parent.docId, name);
                            a.h.post(() -> {
                                if (path.isEmpty() || !current().docId.equals(parent.docId)) {
                                    path.clear();
                                    Prefs.Dest loc = location(parent.tree);
                                    path.add(loc);
                                    if (!loc.docId.equals(parent.docId)) path.add(parent);
                                }
                                path.add(child(parent, id, name));
                                refresh();
                            });
                        } catch (Exception e) {
                            a.log("Could not create album " + name + ": " + e.getMessage());
                            a.h.post(() -> new AlertDialog.Builder(a).setTitle("Couldn't create the album")
                                    .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        et.requestFocus();
    }

    private void go() {
        Prefs.Dest cur = current();
        if (cur == null) return;
        a.confirmTransfer(todo, move, cur);
    }
}
