package com.lumastack.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Small self-contained image viewer with pinch zoom, pan, and double-tap reset. */
final class ZoomImageView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float scale = 1f;
    private float lastX, lastY;
    private long lastTap;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(1f, Math.min(5f, scale * detector.getScaleFactor()));
                float factor = next / scale;
                transform.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                scale = next;
                setImageMatrix(transform);
                return true;
            }
        });
        setOnTouchListener((view, event) -> handleTouch(event));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetToFit();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetToFit);
    }

    void resetToFit() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = drawable.getIntrinsicWidth(), dh = drawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float fit = Math.min(getWidth() / dw, getHeight() / dh);
        float tx = (getWidth() - dw * fit) / 2f;
        float ty = (getHeight() - dh * fit) / 2f;
        transform.reset();
        transform.postScale(fit, fit);
        transform.postTranslate(tx, ty);
        scale = 1f;
        setImageMatrix(transform);
    }

    private boolean handleTouch(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                long now = System.currentTimeMillis();
                if (now - lastTap < 280) resetToFit();
                lastTap = now;
                lastX = event.getX(); lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && scale > 1f) {
                    transform.postTranslate(event.getX() - lastX, event.getY() - lastY);
                    setImageMatrix(transform);
                }
                lastX = event.getX(); lastY = event.getY();
                return true;
            default:
                return true;
        }
    }
}
