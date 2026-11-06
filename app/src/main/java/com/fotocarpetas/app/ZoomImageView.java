package com.fotocarpetas.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** ImageView con zoom (pellizcar o doble toque), arrastre y deslizamiento para cambiar de foto. */
public class ZoomImageView extends ImageView {

    interface Listener {
        /** direction: +1 = siguiente, -1 = anterior */
        void onSwipe(int direction);

        void onTap();
    }

    private static final float MAX_ZOOM = 6f;

    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private float zoom = 1f;
    private Listener listener;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomImageView(Context ctx) {
        super(ctx);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float target = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                float f = target / zoom;
                zoom = target;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixBounds();
                return true;
            }
        });
        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1.05f) {
                    resetZoom();
                } else {
                    float f = 2.5f;
                    zoom = f;
                    matrix.postScale(f, f, e.getX(), e.getY());
                    fixBounds();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom > 1.01f) {
                    matrix.postTranslate(-dx, -dy);
                    fixBounds();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (zoom <= 1.01f && listener != null
                        && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 600) {
                    listener.onSwipe(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isZoomed() {
        return zoom > 1.01f;
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    void resetZoom() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.min(getWidth() / dw, getHeight() / dh);
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((getWidth() - dw * s) / 2f, (getHeight() - dh * s) / 2f);
        zoom = 1f;
        setImageMatrix(matrix);
    }

    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        rect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (rect.width() <= w) dx = (w - rect.width()) / 2f - rect.left;
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < w) dx = w - rect.right;
        if (rect.height() <= h) dy = (h - rect.height()) / 2f - rect.top;
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < h) dy = h - rect.bottom;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return true;
    }
}
