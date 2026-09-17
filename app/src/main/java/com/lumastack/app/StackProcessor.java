package com.lumastack.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Local, translation-aligned exposure fusion. No network or native service is used. */
final class StackProcessor {
    interface Progress { void update(int completed, int total); }
    private static final int MAX_EDGE = 2048;
    private static final int PREVIEW_EDGE = 180;

    static Bitmap process(Context context, List<Uri> images, Progress progress) throws IOException {
        if (images.size() < 2) throw new IOException("Choose at least two photos.");
        Bitmap first = decode(context, images.get(0), MAX_EDGE);
        int width = first.getWidth(), height = first.getHeight();
        int size = width * height;
        float[] red = new float[size], green = new float[size], blue = new float[size], weights = new float[size];
        int[] reference = sampleLuma(first, PREVIEW_EDGE);
        int sampleWidth = Math.max(1, Math.round(width * (float) PREVIEW_EDGE / Math.max(width, height)));
        int sampleHeight = Math.max(1, Math.round(height * (float) PREVIEW_EDGE / Math.max(width, height)));
        // Scaling can round by one pixel; use the actual thumbnail dimensions instead.
        Bitmap thumb = Bitmap.createScaledBitmap(first, sampleWidth, sampleHeight, true);
        sampleWidth = thumb.getWidth(); sampleHeight = thumb.getHeight(); thumb.recycle();

        try {
            for (int n = 0; n < images.size(); n++) {
                Bitmap current = n == 0 ? first : decode(context, images.get(n), MAX_EDGE);
                if (current.getWidth() != width || current.getHeight() != height) {
                    Bitmap scaled = Bitmap.createScaledBitmap(current, width, height, true);
                    current.recycle(); current = scaled;
                }
                int dx = 0, dy = 0;
                if (n > 0) {
                    int[] moving = sampleLuma(current, PREVIEW_EDGE);
                    int[] shift = findShift(reference, moving, sampleWidth, sampleHeight);
                    dx = Math.round(shift[0] * width / (float) sampleWidth);
                    dy = Math.round(shift[1] * height / (float) sampleHeight);
                }
                accumulate(current, dx, dy, red, green, blue, weights, width, height);
                if (current != first) current.recycle();
                progress.update(n + 1, images.size());
            }
            int[] output = new int[size];
            for (int i = 0; i < size; i++) {
                if (weights[i] < 0.00001f) { output[i] = 0xff000000; continue; }
                float r = red[i] / weights[i], g = green[i] / weights[i], b = blue[i] / weights[i];
                // Gentle S curve, warmth, and saturation: values remain bounded in sRGB.
                r = tone(r * 1.025f); g = tone(g * 1.008f); b = tone(b * 0.985f);
                float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = luma + (r - luma) * 1.08f;
                g = luma + (g - luma) * 1.08f;
                b = luma + (b - luma) * 1.08f;
                output[i] = 0xff000000 | (toByte(r) << 16) | (toByte(g) << 8) | toByte(b);
            }
            return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888);
        } finally {
            first.recycle();
        }
    }

    private static float tone(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return Math.max(0f, Math.min(1f, v + 0.075f * v * (1f - v) * (2f * v - 1f)));
    }
    private static int toByte(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    private static void accumulate(Bitmap image, int dx, int dy, float[] red, float[] green,
                                   float[] blue, float[] weights, int w, int h) {
        int[] row = new int[w];
        for (int y = Math.max(0, -dy); y < Math.min(h, h - dy); y++) {
            image.getPixels(row, 0, w, 0, y + dy, w, 1);
            for (int x = Math.max(0, -dx); x < Math.min(w, w - dx); x++) {
                int color = row[x + dx], i = y * w + x;
                float r = ((color >> 16) & 255) / 255f;
                float g = ((color >> 8) & 255) / 255f;
                float b = (color & 255) / 255f;
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                // Prefer midtones while retaining all samples, including highlights and shadows.
                float weight = 0.08f + (float) Math.exp(-Math.pow((lum - 0.5f) / 0.28f, 2));
                red[i] += weight * r; green[i] += weight * g; blue[i] += weight * b;
                weights[i] += weight;
            }
        }
    }

    private static int[] sampleLuma(Bitmap source, int maxEdge) {
        int w = Math.max(1, Math.round(source.getWidth() * (float) maxEdge /
                Math.max(source.getWidth(), source.getHeight())));
        int h = Math.max(1, Math.round(source.getHeight() * (float) maxEdge /
                Math.max(source.getWidth(), source.getHeight())));
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        int[] pixels = new int[w * h], out = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            out[i] = (54 * ((c >> 16) & 255) + 183 * ((c >> 8) & 255) + 19 * (c & 255)) >> 8;
        }
        if (small != source) small.recycle();
        return out;
    }

    private static int[] findShift(int[] a, int[] b, int w, int h) {
        int bestX = 0, bestY = 0;
        long best = Long.MAX_VALUE;
        int radius = Math.min(16, Math.min(w, h) / 5);
        // Compare gradients: global exposure changes affect the score less than raw brightness.
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                long error = 0; int count = 0;
                for (int y = radius + 1; y < h - radius - 1; y += 2) {
                    for (int x = radius + 1; x < w - radius - 1; x += 2) {
                        int i = y * w + x, j = (y + dy) * w + x + dx;
                        int gxA = a[i + 1] - a[i - 1], gyA = a[i + w] - a[i - w];
                        int gxB = b[j + 1] - b[j - 1], gyB = b[j + w] - b[j - w];
                        error += Math.abs(gxA - gxB) + Math.abs(gyA - gyB);
                        count++;
                    }
                }
                if (count > 0 && error / count < best) {
                    best = error / count; bestX = dx; bestY = dy;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private static Bitmap decode(Context context, Uri uri, int maxEdge) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Could not read a photo.");
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) throw new IOException("Could not decode a photo.");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
        }
        if (!matrix.isIdentity()) {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle(); bitmap = rotated;
        }
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxEdge) {
            float scale = maxEdge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            bitmap.recycle(); bitmap = smaller;
        }
        return bitmap;
    }
}
