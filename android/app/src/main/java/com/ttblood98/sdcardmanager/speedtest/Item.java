package com.ttblood98.sdcardmanager.speedtest;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** One file on the card. */
final class Item {
    static final int T_RAW = 0, T_JPG = 1, T_PNG = 2, T_OTHER = 3;
    static final String[] TYPE_NAMES = {"RAW", "JPEG", "PNG", "Other"};
    private static final Set<String> RAW_EXT = new HashSet<>(Arrays.asList(
            "nef", "nrw", "dng", "cr2", "cr3", "crw", "arw", "raf", "orf", "rw2", "pef", "srw", "x3f", "raw"));

    String name;
    Uri uri;
    long size;
    long mtime;
    int type;
    boolean selected;

    String key() { return uri.toString(); }

    String ext() {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1).toUpperCase(Locale.ROOT) : "";
    }

    static int typeOf(String name) {
        int i = name.lastIndexOf('.');
        String x = i > 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
        if (RAW_EXT.contains(x)) return T_RAW;
        if (x.equals("jpg") || x.equals("jpeg")) return T_JPG;
        if (x.equals("png")) return T_PNG;
        return T_OTHER;
    }
}
