package com.ttblood98.sdcardmanager.speedtest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours, sizes and small view factories, so every screen looks consistent. All UI is built in code. */
final class Ui {
    final Context c;
    final float dp;
    final boolean night;
    final int bg, surface, surface2, text, textDim, accent, onAccent, divider, danger, scrim, selected;

    Ui(Context c) {
        this.c = c;
        dp = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bg = 0xFF0F1115; surface = 0xFF1A1D23; surface2 = 0xFF252931; text = 0xFFEDEFF3; textDim = 0xFF9BA1AC;
            accent = 0xFF5AA9FF; onAccent = 0xFF0A1A2C; divider = 0xFF2C313A; danger = 0xFFFF7A7A;
        } else {
            bg = 0xFFF6F7F9; surface = 0xFFFFFFFF; surface2 = 0xFFECEEF2; text = 0xFF15171C; textDim = 0xFF5F6670;
            accent = 0xFF1F6FEB; onAccent = 0xFFFFFFFF; divider = 0xFFDDE1E7; danger = 0xFFC62828;
        }
        scrim = 0x99000000;
        selected = (accent & 0x00FFFFFF) | 0x55000000;
    }

    int px(float v) { return Math.round(v * dp); }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        if (strokeColor != 0) g.setStroke(px(1), strokeColor);
        return g;
    }

    Drawable ripple(Drawable content, int rippleColor) {
        Drawable mask = content != null ? content : new ColorDrawable(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    int rippleColor() { return night ? 0x33FFFFFF : 0x22000000; }

    /** Filled, rounded, accent-coloured button. */
    TextView primaryButton(String s, View.OnClickListener l) {
        TextView b = text(s, 15, onAccent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(18), px(12), px(18), px(12));
        b.setMinHeight(px(48));
        b.setBackground(ripple(round(accent, 24, 0), 0x33FFFFFF));
        b.setOnClickListener(l);
        return b;
    }

    /** Outlined, rounded button. */
    TextView secondaryButton(String s, View.OnClickListener l) {
        TextView b = text(s, 15, accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(18), px(12), px(18), px(12));
        b.setMinHeight(px(48));
        b.setBackground(ripple(round(0x00000000, 24, divider), rippleColor()));
        b.setOnClickListener(l);
        return b;
    }

    /** Text-only button, e.g. in a top bar. */
    TextView flatButton(String s, View.OnClickListener l) {
        TextView b = text(s, 15, accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(14), 0, px(14), 0);
        b.setMinHeight(px(48));
        b.setMinWidth(px(48));
        b.setBackground(ripple(null, rippleColor()));
        b.setOnClickListener(l);
        return b;
    }

    /** Square 48dp touch target showing a glyph. */
    TextView iconButton(String glyph, String description, View.OnClickListener l) {
        TextView b = text(glyph, 22, text, false);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setBackground(ripple(null, rippleColor()));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(48), px(48)));
        return b;
    }

    TextView chip(String s, View.OnClickListener l) {
        TextView t = text(s, 14, text, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        t.setMinHeight(px(36));
        t.setSingleLine(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        p.setMargins(0, 0, px(8), 0);
        t.setLayoutParams(p);
        setChip(t, false);
        return t;
    }

    void setChip(TextView t, boolean on) {
        t.setTextColor(on ? onAccent : text);
        t.setBackground(ripple(round(on ? accent : surface, 18, on ? 0 : divider), rippleColor()));
    }

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, Math.max(1, px(1) / 2 + 1)));
        return v;
    }

    View spacer() {
        View v = new View(c);
        v.setLayoutParams(weight(1));
        return v;
    }

    /** 56dp top bar with an optional leading icon, a title/subtitle and trailing actions. */
    LinearLayout topBar() {
        LinearLayout bar = hbox();
        bar.setBackgroundColor(surface);
        bar.setPadding(px(4), 0, px(4), 0);
        bar.setMinimumHeight(px(56));
        bar.setElevation(px(2));
        return bar;
    }

    /** Title + optional subtitle block for a top bar. */
    LinearLayout titleBlock(TextView title, TextView subtitle) {
        LinearLayout col = vbox();
        col.setPadding(px(8), 0, px(8), 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        if (subtitle != null) {
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.START);
            col.addView(subtitle);
        }
        col.setLayoutParams(weight(1));
        return col;
    }

    /** A tappable list row: title, optional subtitle, optional trailing view. */
    LinearLayout listRow(String leading, String title, String subtitle, View trailing, View.OnClickListener l) {
        LinearLayout row = hbox();
        row.setPadding(px(16), px(10), px(12), px(10));
        row.setMinimumHeight(px(56));
        if (l != null) {
            row.setBackground(ripple(null, rippleColor()));
            row.setOnClickListener(l);
        }
        if (leading != null) {
            TextView lead = text(leading, 20, textDim, false);
            lead.setGravity(Gravity.CENTER);
            row.addView(lead, lp(px(40), WRAP));
        }
        LinearLayout col = vbox();
        TextView t = text(title, 16, text, false);
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 13, textDim, false);
            col.addView(s);
        }
        row.addView(col, weight(1));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    TextView sectionHeader(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.ROOT), 12, accent, true);
        t.setPadding(px(16), px(20), px(16), px(6));
        t.setLetterSpacing(0.06f);
        return t;
    }

    static String fmtSize(long n) {
        if (n >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0);
        if (n >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024) return (n / 1024) + " KB";
        return n + " B";
    }
}
