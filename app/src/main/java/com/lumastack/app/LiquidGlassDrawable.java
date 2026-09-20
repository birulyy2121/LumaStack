package com.lumastack.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A lightweight approximation of refractive glass that works from Android 6 onward. */
public final class LiquidGlassDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path rimPath = new Path();
    private final float density;
    private final float radius;
    private final int tint;
    private final boolean deep;
    private float touchX = -1f;
    private float touchY = -1f;
    private float energy;
    private int drawableAlpha = 255;

    public LiquidGlassDrawable(float density, float radiusDp, int tint, boolean deep) {
        this.density = density;
        this.radius = radiusDp * density;
        this.tint = tint;
        this.deep = deep;
    }

    public void setTouchLight(float x, float y, float amount) {
        touchX = x;
        touchY = y;
        energy = Math.max(0f, Math.min(1f, amount));
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        RectF box = new RectF(bounds);
        float inset = density * (deep ? 2.5f : 1.5f);
        RectF face = new RectF(box.left + inset, box.top + inset,
                box.right - inset, box.bottom - inset * 1.5f);

        // The broad lower shadow gives thick glass its lifted, lens-like edge.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(face.centerX(), face.bottom + density * 5f,
                Math.max(face.width(), face.height()) * .72f,
                new int[]{a(Color.argb(deep ? 90 : 62, 0, 0, 0)), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(face.left - inset, face.top + density * 3f,
                face.right + inset, face.bottom + density * 7f), radius + inset, radius + inset, paint);

        int baseAlpha = deep ? 112 : 168;
        int top = mix(tint, Color.WHITE, deep ? .18f : .24f, baseAlpha);
        int middle = mix(tint, Color.rgb(16, 31, 48), .42f, deep ? 78 : 132);
        int bottom = mix(tint, Color.BLACK, .35f, deep ? 118 : 185);
        paint.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom,
                new int[]{top, middle, bottom}, new float[]{0f, .47f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius, radius, paint);

        canvas.save();
        canvas.clipPath(roundPath(face, radius));

        // A soft caustic pools at the upper edge, as if light is being concentrated by a lens.
        float highlightX = touchX >= 0 ? touchX : face.left + face.width() * .26f;
        float highlightY = touchY >= 0 ? touchY : face.top + face.height() * .10f;
        float highlightRadius = Math.max(face.width() * .72f, density * 90f);
        int highlightAlpha = (int) (deep ? 44 + energy * 54 : 60 + energy * 75);
        paint.setShader(new RadialGradient(highlightX, highlightY, highlightRadius,
                new int[]{a(Color.argb(highlightAlpha, 236, 253, 255)),
                        a(Color.argb(highlightAlpha / 3, 107, 221, 255)), Color.TRANSPARENT},
                new float[]{0f, .26f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(highlightX, highlightY, highlightRadius, paint);

        // Bottom density makes the material feel thicker instead of uniformly transparent.
        paint.setShader(new LinearGradient(0, face.top + face.height() * .42f, 0, face.bottom,
                new int[]{Color.TRANSPARENT, a(Color.argb(deep ? 62 : 42, 1, 6, 14))},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(face, paint);
        canvas.restore();

        // Split bright and dark rims mimic the refracted edge of shaped glass.
        paint.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom,
                new int[]{a(Color.argb(deep ? 160 : 190, 247, 254, 255)),
                        a(Color.argb(70, 128, 222, 255)),
                        a(Color.argb(35, 175, 116, 255)),
                        a(Color.argb(deep ? 95 : 120, 1, 7, 17))},
                new float[]{0f, .33f, .70f, 1f}, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * (deep ? 1.35f : 1.1f));
        canvas.drawRoundRect(face, radius, radius, paint);

        RectF inner = new RectF(face.left + density * 2f, face.top + density * 2f,
                face.right - density * 2f, face.bottom - density * 2f);
        paint.setShader(new LinearGradient(inner.left, inner.top, inner.left, inner.bottom,
                new int[]{a(Color.argb(88, 255, 255, 255)), Color.TRANSPARENT,
                        a(Color.argb(48, 0, 0, 0))}, null, Shader.TileMode.CLAMP));
        paint.setStrokeWidth(density * .65f);
        canvas.drawRoundRect(inner, Math.max(0, radius - density * 2f),
                Math.max(0, radius - density * 2f), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private Path roundPath(RectF rect, float corner) {
        rimPath.reset();
        rimPath.addRoundRect(rect, corner, corner, Path.Direction.CW);
        return rimPath;
    }

    private int mix(int first, int second, float amount, int alpha) {
        int red = Math.round(Color.red(first) * (1f - amount) + Color.red(second) * amount);
        int green = Math.round(Color.green(first) * (1f - amount) + Color.green(second) * amount);
        int blue = Math.round(Color.blue(first) * (1f - amount) + Color.blue(second) * amount);
        return a(Color.argb(alpha, red, green, blue));
    }

    private int a(int color) {
        return Color.argb(Math.round(Color.alpha(color) * drawableAlpha / 255f),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override public void setAlpha(int alpha) {
        drawableAlpha = alpha;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public void getOutline(Outline outline) {
        Rect bounds = getBounds();
        outline.setRoundRect(bounds, radius);
        outline.setAlpha(deep ? .70f : .88f);
    }
}
