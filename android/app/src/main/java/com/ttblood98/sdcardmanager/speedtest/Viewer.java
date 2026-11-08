package com.ttblood98.sdcardmanager.speedtest;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen photo viewer. Swipe slides between photos (three slots: previous, current, next), pinch or double-tap to
 * zoom, drag to pan, tap to hide the controls. Details (resolution, camera, exposure) are cached and prefetched.
 */
final class Viewer implements MainActivity.Page {
    private final MainActivity a;
    private final Ui ui;
    private int index;
    private final FrameLayout root, pager;
    private final ZoomImageView[] slots = new ZoomImageView[3];
    private final LinearLayout top, bottom;
    private final TextView title, counter, info, details, selBtn;
    private boolean chromeVisible = true;
    private float offset = 0;
    private boolean paging = false, scaling = false;
    private ValueAnimator settleAnim;
    private VelocityTracker vt;
    private final int gap;

    Viewer(MainActivity a, int index) {
        this.a = a;
        this.ui = a.ui;
        this.index = index;
        gap = ui.px(16);
        root = new FrameLayout(a);
        root.setBackgroundColor(0xFF000000);
        pager = new FrameLayout(a);
        for (int k = 0; k < 3; k++) {
            slots[k] = new ZoomImageView(a);
            pager.addView(slots[k], new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        root.addView(pager, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        top = ui.hbox();
        top.setBackgroundColor(0x99000000);
        top.setPadding(ui.px(4), ui.px(4), ui.px(8), ui.px(4));
        TextView back = ui.iconButton("←", "Back", v -> a.onBackPressed());
        back.setTextColor(0xFFFFFFFF);
        top.addView(back);
        LinearLayout tcol = ui.vbox();
        title = ui.text("", 16, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        counter = ui.text("", 12, 0xCCFFFFFF, false);
        tcol.addView(title);
        tcol.addView(counter);
        top.addView(tcol, Ui.weight(1));
        android.widget.ImageView trash = new android.widget.ImageView(a);
        android.graphics.drawable.Drawable ti = a.trashIcon(0xFFFFFFFF, 24);
        if (ti != null) trash.setImageDrawable(ti);
        trash.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        trash.setContentDescription("Delete this photo");
        trash.setBackground(ui.ripple(null, 0x33FFFFFF));
        trash.setOnClickListener(v -> {
            Item e = currentItem();
            if (e != null) a.confirmDeleteOne(e);
        });
        top.addView(trash, new LinearLayout.LayoutParams(ui.px(48), ui.px(48)));
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
        details = ui.text("", 13, 0xCCFFFFFF, false);
        details.setPadding(0, ui.px(4), 0, 0);
        details.setMinLines(3);  // fixed height: the panel never jumps while details load
        bottom.addView(info);
        bottom.addView(details);
        root.addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        final ScaleGestureDetector sd = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                if (paging) return false;
                scaling = true;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector d) {
                slots[1].zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector d) {
                slots[1].settle();
            }
        });
        final GestureDetector gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                chromeVisible = !chromeVisible;
                top.animate().alpha(chromeVisible ? 1f : 0f).setDuration(150).start();
                bottom.animate().alpha(chromeVisible ? 1f : 0f).setDuration(150).start();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                slots[1].toggleZoom(e.getX(), e.getY());
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling || e2.getPointerCount() > 1) return false;
                ZoomImageView cur = slots[1];
                if (!paging && cur.isZoomed()) {
                    boolean outward = (dx > 0 && cur.atRightEdge()) || (dx < 0 && cur.atLeftEdge());
                    if (!outward || Math.abs(dx) < Math.abs(dy)) { cur.panBy(-dx, -dy); return true; }
                }
                if (!paging) {
                    if (Math.abs(dx) < Math.abs(dy)) return false;
                    paging = true;
                }
                float step = -dx;
                boolean edge = (offset + step > 0 && Viewer.this.index == 0)
                        || (offset + step < 0 && Viewer.this.index == a.shown.size() - 1);
                offset += edge ? step * 0.35f : step;
                layoutSlots();
                return true;
            }
        });
        pager.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                if (settleAnim != null && settleAnim.isRunning()) settleAnim.end();
                scaling = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) vt.addMovement(ev);
            sd.onTouchEvent(ev);
            gd.onTouchEvent(ev);
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                float vx = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                if (paging) settle(vx);
                paging = false;
                scaling = false;
            }
            return true;
        });
        pager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutSlots());
        loadAll();
    }

    @Override public View view() { return root; }

    @Override
    public void refresh() {
        if (a.shown.isEmpty()) { a.popPage(); return; }
        index = Math.min(index, a.shown.size() - 1);
        loadAll();
    }

    // ---------------------------------------------------------------- paging

    private void layoutSlots() {
        float w = pager.getWidth() + gap;
        for (int k = 0; k < 3; k++) slots[k].setTranslationX((k - 1) * w + offset);
    }

    private void settle(float vx) {
        float w = pager.getWidth() + gap;
        float fling = ui.px(500);
        int dir = 0;
        if ((offset < -w * 0.25f || (vx < -fling && offset < 0)) && index < a.shown.size() - 1) dir = 1;
        else if ((offset > w * 0.25f || (vx > fling && offset > 0)) && index > 0) dir = -1;
        final float target = dir == 1 ? -w : dir == -1 ? w : 0;
        final int fdir = dir;
        settleAnim = ValueAnimator.ofFloat(offset, target);
        float frac = Math.min(1f, Math.abs(target - offset) / Math.max(1f, w));
        settleAnim.setDuration((long) (120 + 180 * frac));
        settleAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        settleAnim.addUpdateListener(an -> { offset = (float) an.getAnimatedValue(); layoutSlots(); });
        settleAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                offset = 0;
                if (fdir != 0) rotate(fdir);
                layoutSlots();
            }
        });
        settleAnim.start();
    }

    private void rotate(int dir) {
        if (dir == 1) {
            ZoomImageView t = slots[0];
            slots[0] = slots[1];
            slots[1] = slots[2];
            slots[2] = t;
            index++;
            slots[0].resetFit();
            loadSlot(2, index + 1);
        } else {
            ZoomImageView t = slots[2];
            slots[2] = slots[1];
            slots[1] = slots[0];
            slots[0] = t;
            index--;
            slots[2].resetFit();
            loadSlot(0, index - 1);
        }
        onCurrentChanged();
    }

    // ---------------------------------------------------------------- loading

    private void loadAll() {
        loadSlot(1, index);
        loadSlot(2, index + 1);
        loadSlot(0, index - 1);
        onCurrentChanged();
    }

    private int maxDim() {
        return Math.min(2048, Math.max(a.getResources().getDisplayMetrics().widthPixels,
                a.getResources().getDisplayMetrics().heightPixels));
    }

    private void loadSlot(int k, int idx) {
        final ZoomImageView zv = slots[k];
        if (idx < 0 || idx >= a.shown.size()) { zv.setTag(null); zv.setPhoto(null, false); return; }
        final Item e = a.shown.get(idx);
        final String key = e.key();
        boolean same = key.equals(zv.getTag());
        if (same && key.equals(zv.largeKey)) return;
        zv.setTag(key);
        if (!same) { zv.largeKey = null; zv.setPhoto(a.thumbs.cached(e), false); }
        a.thumbs.loadLarge(e, maxDim(), bm -> {
            if (bm != null && key.equals(zv.getTag())) { zv.setPhoto(bm, true); zv.largeKey = key; }
        });
    }

    private void onCurrentChanged() {
        if (index < 0 || index >= a.shown.size()) return;
        Item e = a.shown.get(index);
        title.setText(e.name);
        counter.setText((index + 1) + " of " + a.shown.size());
        info.setText(new SimpleDateFormat("EEEE, MMMM d, yyyy  ·  h:mm a", Locale.getDefault()).format(new Date(e.mtime)));
        details.setText(compose(e, a.thumbs.cachedDetails(e)));
        updateSel();
        fetchDetails(index, true);
        fetchDetails(index + 1, false);
        fetchDetails(index - 1, false);
    }

    private void fetchDetails(int idx, boolean show) {
        if (idx < 0 || idx >= a.shown.size()) return;
        final Item e = a.shown.get(idx);
        if (a.thumbs.cachedDetails(e) != null) return;
        a.bg.execute(() -> {
            String s = readDetails(e);
            a.h.post(() -> {
                a.thumbs.putDetails(e, s);
                if (index >= 0 && index < a.shown.size() && a.shown.get(index) == e) details.setText(compose(e, s));
            });
        });
    }

    private void updateSel() {
        Item e = a.shown.get(index);
        selBtn.setText(e.selected ? "✓ Selected" : "Select");
        selBtn.setBackground(e.selected ? ui.round(ui.accent, 18, 0) : ui.round(0x00000000, 18, 0xAAFFFFFF));
        selBtn.setTextColor(e.selected ? ui.onAccent : 0xFFFFFFFF);
    }

    /**
     * The photo actually on screen. If a swipe is still settling, it is finished first: the index only moves to the
     * new photo when the slide ends, so acting before that would hit the previous photo.
     */
    private Item currentItem() {
        if (settleAnim != null && settleAnim.isRunning()) settleAnim.end();
        if (paging || index < 0 || index >= a.shown.size()) return null;
        Item e = a.shown.get(index);
        return e.key().equals(slots[1].getTag()) ? e : null;
    }

    private void toggleSelect() {
        Item e = currentItem();
        if (e == null) return;
        e.selected = !e.selected;
        updateSel();
        a.onSelectionChanged();
    }

    // ---------------------------------------------------------------- details

    private static final String DOT = "  ·  ";

    /** Line 1: type, size, resolution. Line 2: camera and lens. Line 3: exposure. Shown even before details load. */
    private static String compose(Item e, String det) {
        String[] p = det == null ? new String[]{"", "", ""} : det.split("\n", -1);
        StringBuilder sb = new StringBuilder(Item.TYPE_NAMES[e.type]).append(DOT).append(Ui.fmtSize(e.size));
        if (p.length > 0 && !p[0].isEmpty()) sb.append(DOT).append(p[0]);
        for (int i = 1; i < p.length; i++) if (!p[i].isEmpty()) sb.append('\n').append(p[i]);
        return sb.toString();
    }

    /** "resolution \n camera + lens \n exposure" read from the file (parts may be empty). Background thread. */
    private String readDetails(Item e) {
        StringBuilder sb = new StringBuilder();
        android.media.ExifInterface x = null;
        try (ParcelFileDescriptor pfd = a.getContentResolver().openFileDescriptor(e.uri, "r")) {
            if (pfd != null) x = new android.media.ExifInterface(pfd.getFileDescriptor());
        } catch (Exception ignored) { }

        int w = 0, h = 0;
        if (e.type == Item.T_JPG) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = a.getContentResolver().openInputStream(e.uri)) { BitmapFactory.decodeStream(in, null, o); }
            catch (Exception ignored) { }
            w = o.outWidth;
            h = o.outHeight;
        } else if (e.type == Item.T_RAW) {
            if (x != null) {
                w = x.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_WIDTH, 0);
                h = x.getAttributeInt(android.media.ExifInterface.TAG_IMAGE_LENGTH, 0);
            }
            if (Math.max(w, h) < 1000) {
                int[] p = Thumbs.rawPreviewSize(a.getContentResolver(), e.uri);
                if (p != null) { w = p[0]; h = p[1]; }
            }
        }
        if (x != null) {
            int orient = x.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            if (orient == 6 || orient == 8) { int t = w; w = h; h = t; }
        }
        if (w > 0 && h > 0) {
            sb.append(w).append(" × ").append(h).append(DOT).append(String.format(Locale.US, "%.1f MP", w * (double) h / 1e6));
        }
        if (x == null) return sb + "\n\n";

        String make = x.getAttribute(android.media.ExifInterface.TAG_MAKE);
        String model = x.getAttribute(android.media.ExifInterface.TAG_MODEL);
        StringBuilder cam = new StringBuilder();
        if (model != null) {
            String m = model.trim();
            if (make != null && !m.toLowerCase(Locale.ROOT).startsWith(make.trim().split(" ")[0].toLowerCase(Locale.ROOT))) {
                m = make.trim().split(" ")[0] + " " + m;
            }
            cam.append(m);
        }
        String lens = x.getAttribute("LensModel");
        if (lens != null && !lens.trim().isEmpty()) cam.append(cam.length() > 0 ? DOT : "").append(lens.trim());

        StringBuilder exp = new StringBuilder();
        double fl = x.getAttributeDouble(android.media.ExifInterface.TAG_FOCAL_LENGTH, 0);
        if (fl > 0) exp.append(Math.round(fl)).append(" mm");
        double fn = x.getAttributeDouble(android.media.ExifInterface.TAG_F_NUMBER, 0);
        if (fn > 0) exp.append(exp.length() > 0 ? DOT : "").append(String.format(Locale.US, "f/%.1f", fn));
        double t = x.getAttributeDouble(android.media.ExifInterface.TAG_EXPOSURE_TIME, 0);
        if (t > 0) exp.append(exp.length() > 0 ? DOT : "")
                .append(t >= 1 ? String.format(Locale.US, "%.1f s", t) : "1/" + Math.round(1 / t) + " s");
        String iso = x.getAttribute("ISOSpeedRatings");
        if (iso == null) iso = x.getAttribute("PhotographicSensitivity");
        if (iso != null) exp.append(exp.length() > 0 ? DOT : "").append("ISO ").append(iso);
        return sb + "\n" + cam + "\n" + exp;
    }
}
