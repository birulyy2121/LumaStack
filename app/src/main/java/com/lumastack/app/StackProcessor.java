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
import java.util.ArrayList;
import java.util.List;

/** Local, memory-bounded exposure fusion. No network or native service is used. */
final class StackProcessor {
    interface Progress { void update(int completed, int total); }

    static final class Result {
        final Bitmap bitmap;
        final Bitmap reference;
        final int referenceIndex;
        final List<int[]> offsets;
        final long processingMillis;

        Result(Bitmap bitmap, Bitmap reference, int referenceIndex,
               List<int[]> offsets, long processingMillis) {
            this.bitmap = bitmap;
            this.reference = reference;
            this.referenceIndex = referenceIndex;
            this.offsets = offsets;
            this.processingMillis = processingMillis;
        }
    }

    private static final int MAX_EDGE = 2048;
    private static final int ALIGN_EDGE = 240;
    private static final int SCORE_EDGE = 128;

    static Result process(Context context, List<Uri> images, Progress progress) throws IOException {
        if (images.size() < 2) throw new IOException("Choose at least two photos.");
        long started = System.currentTimeMillis();
        int referenceIndex = chooseReference(context, images);
        Bitmap reference = decode(context, images.get(referenceIndex), MAX_EDGE);
        Bitmap referenceCopy = reference.copy(Bitmap.Config.ARGB_8888, false);
        int width = reference.getWidth(), height = reference.getHeight();
        int size = width * height;
        float[] red = new float[size], green = new float[size], blue = new float[size], weights = new float[size];
        LumaSample referenceSample = sampleLuma(reference, ALIGN_EDGE);
        List<int[]> offsets = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) offsets.add(new int[]{0, 0});

        try {
            for (int n = 0; n < images.size(); n++) {
                Bitmap current = n == referenceIndex ? reference : decode(context, images.get(n), MAX_EDGE);
                if (current.getWidth() != width || current.getHeight() != height) {
                    Bitmap scaled = Bitmap.createScaledBitmap(current, width, height, true);
                    if (current != reference) current.recycle();
                    current = scaled;
                }
                int dx = 0, dy = 0;
                if (n != referenceIndex) {
                    LumaSample moving = sampleLuma(current, ALIGN_EDGE);
                    int[] shift = findShiftPyramid(referenceSample, moving);
                    dx = Math.round(shift[0] * width / (float) referenceSample.width);
                    dy = Math.round(shift[1] * height / (float) referenceSample.height);
                    offsets.set(n, new int[]{dx, dy});
                }
                accumulate(current, reference, dx, dy, n == referenceIndex,
                        red, green, blue, weights, width, height);
                if (current != reference) current.recycle();
                progress.update(n + 1, images.size());
            }

            int[] output = new int[size];
            for (int i = 0; i < size; i++) {
                if (weights[i] < 0.00001f) { output[i] = 0xff000000; continue; }
                float r = red[i] / weights[i], g = green[i] / weights[i], b = blue[i] / weights[i];
                r = filmic(r * 1.022f); g = filmic(g * 1.008f); b = filmic(b * 0.988f);
                float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                boolean skin = r > g && g > b && r - b > 0.055f && r > 0.22f && g > 0.16f;
                float saturation = skin ? 1.035f : 1.095f;
                r = luma + (r - luma) * saturation;
                g = luma + (g - luma) * saturation;
                b = luma + (b - luma) * saturation;
                output[i] = 0xff000000 | (toByte(r) << 16) | (toByte(g) << 8) | toByte(b);
            }
            Bitmap fused = Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888);
            return new Result(fused, referenceCopy, referenceIndex, offsets,
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            referenceCopy.recycle();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage() == null ? "Image processing failed." : e.getMessage(), e);
        } finally {
            reference.recycle();
        }
    }

    private static int chooseReference(Context context, List<Uri> images) throws IOException {
        int bestIndex = 0;
        double bestScore = -1;
        for (int i = 0; i < images.size(); i++) {
            Bitmap small = decode(context, images.get(i), SCORE_EDGE);
            LumaSample sample = sampleLuma(small, SCORE_EDGE);
            small.recycle();
            double mean = 0;
            for (int value : sample.data) mean += value;
            mean /= Math.max(1, sample.data.length);
            double sharpness = 0;
            for (int y = 1; y < sample.height - 1; y += 2) {
                for (int x = 1; x < sample.width - 1; x += 2) {
                    int p = y * sample.width + x;
                    sharpness += Math.abs(sample.data[p + 1] - sample.data[p - 1]);
                    sharpness += Math.abs(sample.data[p + sample.width] - sample.data[p - sample.width]);
                }
            }
            double midtone = Math.exp(-Math.pow((mean - 128.0) / 68.0, 2));
            double score = sharpness * (0.35 + 0.65 * midtone);
            if (score > bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex;
    }

    private static float filmic(float v) {
        v = clamp(v);
        v += 0.105f * v * (1f - v) * (2f * v - 1f);
        if (v > 0.82f) v = 0.82f + (v - 0.82f) * 0.78f;
        return clamp(v);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int toByte(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    private static void accumulate(Bitmap image, Bitmap reference, int dx, int dy, boolean isReference,
                                   float[] red, float[] green, float[] blue, float[] weights, int w, int h) {
        int[] row = new int[w], previousRow = new int[w], referenceRow = new int[w];
        for (int y = Math.max(0, -dy); y < Math.min(h, h - dy); y++) {
            int sourceY = y + dy;
            image.getPixels(row, 0, w, 0, sourceY, w, 1);
            if (sourceY > 0) image.getPixels(previousRow, 0, w, 0, sourceY - 1, w, 1);
            reference.getPixels(referenceRow, 0, w, 0, y, w, 1);
            for (int x = Math.max(0, -dx); x < Math.min(w, w - dx); x++) {
                int sourceX = x + dx;
                int color = row[sourceX], i = y * w + x;
                float r = ((color >> 16) & 255) / 255f;
                float g = ((color >> 8) & 255) / 255f;
                float b = (color & 255) / 255f;
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float exposure = (float) Math.exp(-((square(r - .5f) + square(g - .5f) + square(b - .5f)) / .18f));
                float mean = (r + g + b) / 3f;
                float saturation = (float) Math.sqrt((square(r - mean) + square(g - mean) + square(b - mean)) / 3f);
                float leftLum = sourceX > 0 ? luma(row[sourceX - 1]) : lum;
                float upLum = sourceY > 0 ? luma(previousRow[sourceX]) : lum;
                float contrast = Math.min(1f, (Math.abs(lum - leftLum) + Math.abs(lum - upLum)) * 4f);
                float weight = .035f + exposure * (.72f + .55f * saturation + .72f * contrast);

                if (!isReference) {
                    int ref = referenceRow[x];
                    float rr = ((ref >> 16) & 255) / 255f;
                    float rg = ((ref >> 8) & 255) / 255f;
                    float rb = (ref & 255) / 255f;
                    float refLum = Math.max(.04f, 0.2126f * rr + 0.7152f * rg + 0.0722f * rb);
                    float curLum = Math.max(.04f, lum);
                    float chromaDiff = square(r / curLum - rr / refLum)
                            + square(g / curLum - rg / refLum) + square(b / curLum - rb / refLum);
                    float ghostConfidence = (float) Math.exp(-chromaDiff / .34f);
                    weight *= .2f + .8f * ghostConfidence;
                }

                red[i] += weight * r; green[i] += weight * g; blue[i] += weight * b;
                weights[i] += weight;
            }
        }
    }

    private static float square(float value) { return value * value; }
    private static float luma(int color) {
        return (0.2126f * ((color >> 16) & 255) + 0.7152f * ((color >> 8) & 255)
                + 0.0722f * (color & 255)) / 255f;
    }

    private static final class LumaSample {
        final int[] data;
        final int width, height;
        LumaSample(int[] data, int width, int height) {
            this.data = data; this.width = width; this.height = height;
        }
    }

    private static LumaSample sampleLuma(Bitmap source, int maxEdge) {
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
        return new LumaSample(out, w, h);
    }

    private static int[] findShiftPyramid(LumaSample reference, LumaSample moving) {
        int w = Math.min(reference.width, moving.width), h = Math.min(reference.height, moving.height);
        if (w < 16 || h < 16) return new int[]{0, 0};
        int coarseX = 0, coarseY = 0;
        long best = Long.MAX_VALUE;
        int coarseRadius = Math.min(12, Math.min(w, h) / 6);
        for (int dy = -coarseRadius; dy <= coarseRadius; dy += 2) {
            for (int dx = -coarseRadius; dx <= coarseRadius; dx += 2) {
                long error = gradientError(reference.data, moving.data, w, h, dx, dy, 4);
                if (error < best) { best = error; coarseX = dx; coarseY = dy; }
            }
        }
        int bestX = coarseX, bestY = coarseY;
        best = Long.MAX_VALUE;
        for (int dy = coarseY - 2; dy <= coarseY + 2; dy++) {
            for (int dx = coarseX - 2; dx <= coarseX + 2; dx++) {
                long error = gradientError(reference.data, moving.data, w, h, dx, dy, 2);
                if (error < best) { best = error; bestX = dx; bestY = dy; }
            }
        }
        return new int[]{bestX, bestY};
    }

    private static long gradientError(int[] a, int[] b, int w, int h, int dx, int dy, int step) {
        int margin = Math.max(Math.abs(dx), Math.abs(dy)) + 2;
        long error = 0; int count = 0;
        for (int y = margin; y < h - margin; y += step) {
            for (int x = margin; x < w - margin; x += step) {
                int i = y * w + x, j = (y + dy) * w + x + dx;
                int gxA = a[i + 1] - a[i - 1], gyA = a[i + w] - a[i - w];
                int gxB = b[j + 1] - b[j - 1], gyB = b[j + w] - b[j - w];
                error += Math.abs(gxA - gxB) + Math.abs(gyA - gyB);
                count++;
            }
        }
        return count == 0 ? Long.MAX_VALUE : error / count;
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
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
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
