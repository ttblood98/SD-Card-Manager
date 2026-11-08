package com.ttblood98.sdcardmanager.speedtest;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/** An ImageView that fits the photo to the screen and supports zoom (pinch / double-tap) and panning. */
final class ZoomImageView extends ImageView {
    private final Matrix m = new Matrix();
    private final float[] vals = new float[9];
    private float base = 1f;
    private int bw, bh;
    private ValueAnimator anim;
    /** Key of the photo whose full-size preview is currently shown (so it is not decoded again). */
    String largeKey;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
    }

    /** Sets a new bitmap. If it is the same photo at another resolution and we are zoomed, the zoom is kept. */
    void setPhoto(Bitmap b, boolean samePhoto) {
        if (anim != null) anim.cancel();
        if (b == null) {
            bw = bh = 0;
            super.setImageBitmap(null);
            return;
        }
        if (samePhoto && bw > 0 && isZoomed() && getWidth() > 0) {
            float r = bw / (float) b.getWidth();
            m.preScale(r, r);
            bw = b.getWidth();
            bh = b.getHeight();
            super.setImageBitmap(b);
            clamp();
            setImageMatrix(m);
        } else {
            bw = b.getWidth();
            bh = b.getHeight();
            super.setImageBitmap(b);
            resetFit();
        }
    }

    void resetFit() {
        if (anim != null) anim.cancel();
        applyFit();
    }

    private void applyFit() {
        if (getWidth() == 0 || getHeight() == 0 || bw == 0) return;
        base = Math.min(getWidth() / (float) bw, getHeight() / (float) bh);
        m.setScale(base, base);
        m.postTranslate((getWidth() - bw * base) / 2f, (getHeight() - bh * base) / 2f);
        setImageMatrix(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resetFit();
    }

    private float scale() {
        m.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    boolean isZoomed() { return bw > 0 && scale() > base * 1.02f; }

    void zoomBy(float f, float fx, float fy) {
        if (bw == 0) return;
        float s = scale(), t = s * f;
        float min = base * 0.85f, max = base * 8f;
        if (t < min) f = min / s;
        else if (t > max) f = max / s;
        m.postScale(f, f, fx, fy);
        clamp();
        setImageMatrix(m);
    }

    /** After a pinch ends: spring back to fit if zoomed out below fit. */
    void settle() {
        if (bw > 0 && scale() < base) animateTo(base, getWidth() / 2f, getHeight() / 2f);
    }

    void panBy(float dx, float dy) {
        if (bw == 0) return;
        m.postTranslate(dx, dy);
        clamp();
        setImageMatrix(m);
    }

    private RectF rect() {
        RectF r = new RectF(0, 0, bw, bh);
        m.mapRect(r);
        return r;
    }

    boolean atLeftEdge() { return rect().left >= -1; }

    boolean atRightEdge() { return rect().right <= getWidth() + 1; }

    private void clamp() {
        RectF r = rect();
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
    }

    void toggleZoom(float fx, float fy) {
        if (bw == 0) return;
        if (isZoomed()) animateTo(base, fx, fy);
        else animateTo(base * 2.5f, fx, fy);
    }

    private void animateTo(final float target, final float fx, final float fy) {
        if (anim != null) anim.cancel();
        final float start = scale();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float want = start + (target - start) * t;
            float f = want / scale();
            m.postScale(f, f, fx, fy);
            clamp();
            setImageMatrix(m);
            if (t >= 1f && Math.abs(target - base) < 1e-4) applyFit();
        });
        anim.start();
    }
}
