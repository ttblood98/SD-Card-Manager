package com.ttblood98.sdcardmanager.speedtest;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Full-screen photo viewer: swipe left/right, tap to show/hide controls, select from here. */
final class Viewer implements MainActivity.Page {
    private final MainActivity a;
    private final Ui ui;
    private int index;
    private int token = 0;
    private final FrameLayout root;
    private final ImageView img;
    private final LinearLayout top, bottom;
    private final TextView title, info, exif, selBtn, counter;
    private boolean chromeVisible = true;

    Viewer(MainActivity a, int index) {
        this.a = a;
        this.ui = a.ui;
        this.index = index;
        root = new FrameLayout(a);
        root.setBackgroundColor(0xFF000000);
        img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        top = ui.hbox();
        top.setBackgroundColor(0x99000000);
        top.setPadding(ui.px(4), ui.px(4), ui.px(8), ui.px(4));
        TextView back = ui.iconButton("←", "Back", v -> a.onBackPressed());
        back.setTextColor(0xFFFFFFFF);
        top.addView(back);
        LinearLayout tcol = ui.vbox();
        title = ui.text("", 16, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        counter = ui.text("", 12, 0xCCFFFFFF, false);
        tcol.addView(title);
        tcol.addView(counter);
        top.addView(tcol, Ui.weight(1));
        selBtn = ui.text("", 14, 0xFFFFFFFF, true);
        selBtn.setGravity(Gravity.CENTER);
        selBtn.setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(8));
        selBtn.setOnClickListener(v -> toggleSelect());
        top.addView(selBtn);
        root.addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        bottom = ui.vbox();
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(ui.px(16), ui.px(10), ui.px(16), ui.px(14));
        info = ui.text("", 14, 0xFFFFFFFF, false);
        exif = ui.text("", 13, 0xCCFFFFFF, false);
        exif.setPadding(0, ui.px(4), 0, 0);
        bottom.addView(info);
        bottom.addView(exif);
        TextView hint = ui.text("Swipe for next / previous", 11, 0x88FFFFFF, false);
        hint.setPadding(0, ui.px(6), 0, 0);
        bottom.addView(hint);
        root.addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                chromeVisible = !chromeVisible;
                top.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
                bottom.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > ui.px(400)) {
                    show(vx < 0 ? Viewer.this.index + 1 : Viewer.this.index - 1);
                    return true;
                }
                return false;
            }
        });
        img.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        show(index);
    }

    @Override public View view() { return root; }

    @Override
    public void refresh() {
        if (a.shown.isEmpty()) { a.popPage(); return; }
        show(Math.min(index, a.shown.size() - 1));
    }

    private void show(int i) {
        if (i < 0 || i >= a.shown.size()) return;
        index = i;
        final Item e = a.shown.get(i);
        final int my = ++token;
        title.setText(e.name);
        counter.setText((i + 1) + " of " + a.shown.size());
        info.setText(Item.TYPE_NAMES[e.type] + " · " + Ui.fmtSize(e.size) + " · "
                + new SimpleDateFormat("EEE, MMM d, yyyy  HH:mm", Locale.getDefault()).format(new Date(e.mtime)));
        exif.setText("");
        updateSel();
        Bitmap small = a.thumbs.cached(e);
        img.setImageBitmap(small);
        int maxDim = Math.min(2048, Math.max(a.getResources().getDisplayMetrics().widthPixels,
                a.getResources().getDisplayMetrics().heightPixels));
        a.thumbs.loadLarge(e, maxDim, bm -> { if (my == token && bm != null) img.setImageBitmap(bm); });
        if (e.type != Item.T_OTHER) {
            a.bg.execute(() -> {
                String s = readExif(e);
                a.h.post(() -> { if (my == token) exif.setText(s); });
            });
        }
    }

    private void updateSel() {
        Item e = a.shown.get(index);
        selBtn.setText(e.selected ? "✓ Selected" : "Select");
        selBtn.setBackground(e.selected ? ui.round(ui.accent, 18, 0) : ui.round(0x00000000, 18, 0xAAFFFFFF));
        selBtn.setTextColor(e.selected ? ui.onAccent : 0xFFFFFFFF);
    }

    private void toggleSelect() {
        Item e = a.shown.get(index);
        e.selected = !e.selected;
        updateSel();
        a.onSelectionChanged();
    }

    /** Camera, lens and exposure from the photo's metadata (works for JPEG and most RAW files). */
    private String readExif(Item e) {
        try (ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(e.uri, "r")) {
            if (pfd == null) return "";
            android.media.ExifInterface x = new android.media.ExifInterface(pfd.getFileDescriptor());
            StringBuilder sb = new StringBuilder();
            String make = x.getAttribute(android.media.ExifInterface.TAG_MAKE);
            String model = x.getAttribute(android.media.ExifInterface.TAG_MODEL);
            if (model != null) {
                String cam = model.trim();
                if (make != null && !cam.toLowerCase(Locale.ROOT).startsWith(make.trim().split(" ")[0].toLowerCase(Locale.ROOT))) {
                    cam = make.trim() + " " + cam;
                }
                sb.append(cam);
            }
            String lens = x.getAttribute("LensModel");
            if (lens != null && !lens.trim().isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(lens.trim());
            StringBuilder exp = new StringBuilder();
            double fl = x.getAttributeDouble(android.media.ExifInterface.TAG_FOCAL_LENGTH, 0);
            if (fl > 0) exp.append(Math.round(fl)).append(" mm");
            double fn = x.getAttributeDouble(android.media.ExifInterface.TAG_F_NUMBER, 0);
            if (fn > 0) exp.append(exp.length() > 0 ? "  " : "").append(String.format(Locale.US, "f/%.1f", fn));
            double t = x.getAttributeDouble(android.media.ExifInterface.TAG_EXPOSURE_TIME, 0);
            if (t > 0) exp.append(exp.length() > 0 ? "  " : "").append(t >= 1 ? String.format(Locale.US, "%.1f s", t) : "1/" + Math.round(1 / t) + " s");
            String iso = x.getAttribute("ISOSpeedRatings");
            if (iso == null) iso = x.getAttribute("PhotographicSensitivity");
            if (iso != null) exp.append(exp.length() > 0 ? "  " : "").append("ISO ").append(iso);
            if (exp.length() > 0) sb.append(sb.length() > 0 ? "\n" : "").append(exp);
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
