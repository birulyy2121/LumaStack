package com.lumastack.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Color and light beneath the glass. Kept deliberately slow so it feels alive, not busy. */
public final class LiquidBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private ValueAnimator animator;
    private float phase;
    private boolean glassEnabled = true;

    public LiquidBackdropView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setGlassEnabled(boolean enabled) {
        glassEnabled = enabled;
        if (enabled) startMotion(); else stopMotion();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (glassEnabled) startMotion();
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }

    private void startMotion() {
        if (!isAttachedToWindow() || animator != null) return;
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(18000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (Float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopMotion() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        if (!glassEnabled) {
            canvas.drawColor(Color.rgb(6, 13, 22));
            return;
        }

        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{Color.rgb(3, 9, 18), Color.rgb(8, 20, 36), Color.rgb(10, 10, 27)},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);

        float drift = phase * width * .10f;
        glow(canvas, width * .12f + drift, height * .18f, width * .72f,
                Color.argb(115, 16, 206, 255));
        glow(canvas, width * .96f - drift * .65f, height * .38f, width * .78f,
                Color.argb(100, 129, 80, 255));
        glow(canvas, width * .18f + drift * .35f, height * .72f, width * .82f,
                Color.argb(78, 0, 115, 255));
        glow(canvas, width * .88f - drift * .25f, height * .91f, width * .62f,
                Color.argb(58, 255, 74, 156));

        paint.setShader(new LinearGradient(0, 0, width, 0,
                new int[]{Color.argb(24, 255, 255, 255), Color.TRANSPARENT, Color.argb(18, 130, 214, 255)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
    }

    private void glow(Canvas canvas, float x, float y, float radius, int color) {
        paint.setShader(new RadialGradient(x, y, radius,
                new int[]{color, withAlpha(color, 40), Color.TRANSPARENT},
                new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, radius, paint);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
