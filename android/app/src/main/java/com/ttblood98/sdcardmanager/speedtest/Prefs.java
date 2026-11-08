package com.ttblood98.sdcardmanager.speedtest;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

/** Saved settings: card folder, destination folders, recent destinations, view options. */
final class Prefs {
    private static final String NAME = "sdcard_prefs";
    // keys from earlier versions are kept so existing installs keep their folders
    private static final String K_CARD = "src_tree_v2", K_OLD_DST = "dst_tree_v2", K_ROOTS = "dest_roots_v3",
            K_RECENT = "dest_recent_v3", K_DATE_FOLDERS = "date_folders", K_COLUMNS = "grid_columns", K_SORT = "sort",
            K_TYPES = "types_v2";

    /** A destination folder: a granted tree, a folder inside it, and a readable path for display. */
    static final class Dest {
        Uri tree;
        String docId;
        String path;

        String encode() { return tree + "\t" + docId + "\t" + path; }

        static Dest decode(String s) {
            String[] p = s.split("\t", 3);
            if (p.length < 3) return null;
            Dest d = new Dest();
            d.tree = Uri.parse(p[0]);
            d.docId = p[1];
            d.path = p[2];
            return d;
        }
    }

    private final SharedPreferences p;
    private final ContentResolver cr;

    Prefs(Context c) {
        p = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        cr = c.getContentResolver();
        String old = p.getString(K_OLD_DST, null);
        if (old != null && !p.contains(K_ROOTS)) {
            p.edit().putString(K_ROOTS, old).apply();
        }
    }

    boolean hasPermission(Uri tree) {
        for (UriPermission up : cr.getPersistedUriPermissions()) {
            if (up.getUri().equals(tree) && up.isReadPermission() && up.isWritePermission()) return true;
        }
        return false;
    }

    Uri card() {
        String s = p.getString(K_CARD, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        return hasPermission(u) ? u : null;
    }

    boolean cardWasSet() { return p.getString(K_CARD, null) != null; }

    void setCard(Uri u) { p.edit().putString(K_CARD, u.toString()).apply(); }

    List<Uri> roots() {
        List<Uri> out = new ArrayList<>();
        String s = p.getString(K_ROOTS, "");
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            Uri u = Uri.parse(line);
            if (hasPermission(u) && !out.contains(u)) out.add(u);
        }
        return out;
    }

    void addRoot(Uri u) {
        List<Uri> r = roots();
        if (!r.contains(u)) r.add(u);
        saveRoots(r);
    }

    void removeRoot(Uri u) {
        List<Uri> r = roots();
        r.remove(u);
        saveRoots(r);
        try {
            cr.releasePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
        List<Dest> rec = recents();
        List<Dest> keep = new ArrayList<>();
        for (Dest d : rec) if (!d.tree.equals(u)) keep.add(d);
        saveRecents(keep);
    }

    private void saveRoots(List<Uri> r) {
        StringBuilder sb = new StringBuilder();
        for (Uri u : r) sb.append(u).append('\n');
        p.edit().putString(K_ROOTS, sb.toString()).apply();
    }

    List<Dest> recents() {
        List<Dest> out = new ArrayList<>();
        String s = p.getString(K_RECENT, "");
        for (String line : s.split("\n")) {
            if (line.isEmpty()) continue;
            Dest d = Dest.decode(line);
            if (d != null && hasPermission(d.tree)) out.add(d);
        }
        return out;
    }

    void pushRecent(Dest d) {
        List<Dest> r = recents();
        List<Dest> out = new ArrayList<>();
        out.add(d);
        for (Dest x : r) {
            if (x.tree.equals(d.tree) && x.docId.equals(d.docId)) continue;
            if (out.size() < 6) out.add(x);
        }
        saveRecents(out);
    }

    void clearRecents() { p.edit().remove(K_RECENT).apply(); }

    private void saveRecents(List<Dest> r) {
        StringBuilder sb = new StringBuilder();
        for (Dest d : r) sb.append(d.encode()).append('\n');
        p.edit().putString(K_RECENT, sb.toString()).apply();
    }

    boolean dateFolders() { return p.getBoolean(K_DATE_FOLDERS, false); }

    void setDateFolders(boolean b) { p.edit().putBoolean(K_DATE_FOLDERS, b).apply(); }

    int columns() { return Math.max(2, Math.min(7, p.getInt(K_COLUMNS, 4))); }

    void setColumns(int n) { p.edit().putInt(K_COLUMNS, n).apply(); }

    int sort() { return p.getInt(K_SORT, 0); }

    void setSort(int s) { p.edit().putInt(K_SORT, s).apply(); }

    boolean[] types() {
        String s = p.getString(K_TYPES, "111");
        boolean[] t = new boolean[3];
        for (int i = 0; i < 3; i++) t[i] = i < s.length() && s.charAt(i) == '1';
        return t;
    }

    void setTypes(boolean[] t) {
        StringBuilder sb = new StringBuilder();
        for (boolean b : t) sb.append(b ? '1' : '0');
        p.edit().putString(K_TYPES, sb.toString()).apply();
    }

    /** Friendly name for a granted folder, e.g. "Pictures (phone)" or "DCIM (SD card)". */
    static String rootLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String vol = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        String where = vol.equals("primary") ? "phone" : (vol.isEmpty() ? "" : "SD card");
        String name = path.isEmpty() ? "Storage root" : path;
        return where.isEmpty() ? name : name + " (" + where + ")";
    }
}
