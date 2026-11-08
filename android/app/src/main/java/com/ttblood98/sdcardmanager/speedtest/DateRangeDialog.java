package com.ttblood98.sdcardmanager.speedtest;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;

/**
 * A one-calendar date range picker: tap the first day, then the last day. Days that have photos on the card are
 * marked with a dot, and months can be flipped with the arrows.
 */
final class DateRangeDialog {
    interface Done { void onRange(long fromMs, long toMs, String label); }

    private final MainActivity a;
    private final Ui ui;
    private final Set<Integer> photoDays;  // yyyymmdd
    private final Done done;
    private final Calendar month = Calendar.getInstance();
    private int start = 0, end = 0;  // yyyymmdd, 0 = not chosen
    private TextView summary, monthLabel;
    private LinearLayout grid;
    private AlertDialog dialog;

    DateRangeDialog(MainActivity a, Set<Integer> photoDays, long newestPhoto, Done done) {
        this.a = a;
        this.ui = a.ui;
        this.photoDays = photoDays;
        this.done = done;
        if (newestPhoto > 0) month.setTimeInMillis(newestPhoto);
        month.set(Calendar.DAY_OF_MONTH, 1);
    }

    static int ymd(Calendar c) {
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    private static Calendar fromYmd(int v) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(v / 10000, (v / 100) % 100 - 1, v % 100, 0, 0, 0);
        return c;
    }

    void show() {
        LinearLayout root = ui.vbox();
        root.setPadding(ui.px(16), ui.px(16), ui.px(16), ui.px(4));

        summary = ui.text("", 20, ui.text, true);
        root.addView(summary);
        TextView hint = ui.text("Dots mark days with photos on the card.", 13, ui.textDim, false);
        hint.setPadding(0, ui.px(2), 0, ui.px(10));
        root.addView(hint);

        LinearLayout nav = ui.hbox();
        TextView prev = ui.iconButton("‹", "Previous month", v -> { month.add(Calendar.MONTH, -1); render(); });
        TextView next = ui.iconButton("›", "Next month", v -> { month.add(Calendar.MONTH, 1); render(); });
        monthLabel = ui.text("", 16, ui.text, true);
        monthLabel.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(monthLabel, Ui.weight(1));
        nav.addView(next);
        root.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout dow = ui.hbox();
        String[] names = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
        int first = Calendar.getInstance().getFirstDayOfWeek();
        for (int i = 0; i < 7; i++) {
            int d = (first - 1 + i) % 7 + 1;
            TextView t = ui.text(names[d].substring(0, Math.min(2, names[d].length())), 12, ui.textDim, true);
            t.setGravity(Gravity.CENTER);
            dow.addView(t, Ui.weight(1));
        }
        dow.setPadding(0, ui.px(4), 0, ui.px(4));
        root.addView(dow, Ui.lp(Ui.MATCH, Ui.WRAP));

        grid = ui.vbox();
        root.addView(grid, Ui.lp(Ui.MATCH, Ui.WRAP));

        dialog = new AlertDialog.Builder(a)
                .setView(root)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> apply());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(start != 0);
        });
        render();
        dialog.show();
    }

    private void render() {
        SimpleDateFormat mf = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        monthLabel.setText(mf.format(month.getTime()));
        if (start == 0) summary.setText("Tap the first day");
        else if (end == 0) summary.setText(label(start, start) + "  →  tap the last day");
        else summary.setText(label(start, end));
        if (dialog != null && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(start != 0);
        }

        grid.removeAllViews();
        Calendar c = (Calendar) month.clone();
        int first = c.getFirstDayOfWeek();
        int lead = (c.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int today = ymd(Calendar.getInstance());
        int cell = 0;
        for (int row = 0; row < 6; row++) {
            if (row * 7 >= lead + days) break;
            LinearLayout r = ui.hbox();
            for (int col = 0; col < 7; col++, cell++) {
                int dayNum = cell - lead + 1;
                if (dayNum < 1 || dayNum > days) {
                    r.addView(new View(a), new LinearLayout.LayoutParams(0, ui.px(44), 1f));
                    continue;
                }
                c.set(Calendar.DAY_OF_MONTH, dayNum);
                final int v = ymd(c);
                r.addView(dayCell(dayNum, v, v == today), new LinearLayout.LayoutParams(0, ui.px(44), 1f));
            }
            grid.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
    }

    private View dayCell(int dayNum, final int v, boolean isToday) {
        int lo = start, hi = end == 0 ? start : end;
        boolean endpoint = v == start || (end != 0 && v == end);
        boolean inside = start != 0 && end != 0 && v > lo && v < hi;
        boolean hasPhotos = photoDays.contains(v);

        FrameLayout f = new FrameLayout(a);
        if (inside) {
            View band = new View(a);
            band.setBackgroundColor(ui.selected);
            f.addView(band, new FrameLayout.LayoutParams(Ui.MATCH, ui.px(36), Gravity.CENTER));
        }
        TextView t = ui.text(String.valueOf(dayNum), 15, endpoint ? ui.onAccent : hasPhotos ? ui.text : ui.textDim,
                endpoint || hasPhotos);
        t.setGravity(Gravity.CENTER);
        if (endpoint) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ui.accent);
            t.setBackground(g);
        } else if (isToday) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0);
            g.setStroke(ui.px(1), ui.textDim);
            t.setBackground(g);
        }
        f.addView(t, new FrameLayout.LayoutParams(ui.px(36), ui.px(36), Gravity.CENTER));
        if (hasPhotos && !endpoint) {
            View dot = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ui.accent);
            dot.setBackground(g);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(ui.px(5), ui.px(5), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            dp.setMargins(0, 0, 0, ui.px(2));
            f.addView(dot, dp);
        }
        f.setBackground(ui.ripple(null, ui.rippleColor()));
        f.setOnClickListener(x -> tap(v));
        return f;
    }

    private void tap(int v) {
        if (start == 0 || end != 0) { start = v; end = 0; }
        else if (v < start) { start = v; }
        else end = v;
        render();
    }

    private static String label(int s, int e) {
        Calendar cs = fromYmd(s), ce = fromYmd(e);
        SimpleDateFormat md = new SimpleDateFormat("MMM d", Locale.getDefault());
        SimpleDateFormat mdy = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        if (s == e) return mdy.format(cs.getTime());
        boolean sameYear = cs.get(Calendar.YEAR) == ce.get(Calendar.YEAR);
        return (sameYear ? md : mdy).format(cs.getTime()) + " – " + mdy.format(ce.getTime());
    }

    private void apply() {
        if (start == 0) return;
        int e = end == 0 ? start : end;
        Calendar from = fromYmd(start);
        Calendar to = fromYmd(e);
        to.add(Calendar.DAY_OF_YEAR, 1);
        long toMs = to.getTimeInMillis() - 1;
        String shortLabel = start == e ? new SimpleDateFormat("MMM d", Locale.getDefault()).format(from.getTime())
                : new SimpleDateFormat("MMM d", Locale.getDefault()).format(from.getTime()) + " – "
                + new SimpleDateFormat("MMM d", Locale.getDefault()).format(fromYmd(e).getTime());
        done.onRange(from.getTimeInMillis(), toMs, shortLabel);
        dialog.dismiss();
    }
}
