package io.github.ocrdroid;

/**
 * Non-destructive preprocessing settings applied before OCR. Pure Java so the geometry and
 * colour maths can be unit-tested without Android.
 *
 * <p>The crop is stored as fractions of the rotated frame: the axis-aligned bounding box of the
 * source image after rotation, with exposed corners filled white.
 */
public final class Edits {
    public static final float MAX_STRAIGHTEN = 45f;
    public static final float MIN_CROP = 0.05f;
    /** Gain of the luminance step used for black-and-white: luma >= threshold is white, luma <= threshold - 1 black. */
    static final float BW_GAIN = 255f;
    static final float[] LUMA = {0.299f, 0.587f, 0.114f};

    /** Clockwise quarter turns, 0..3. */
    public int quarterTurns;
    /** Additional clockwise straightening angle in degrees, -45..45. */
    public float straighten;
    public float left, top, right = 1f, bottom = 1f;
    public boolean grayscale;
    public boolean blackWhite;
    public int threshold = 128;

    public Edits copy() {
        Edits copy = new Edits();
        copy.quarterTurns = quarterTurns;
        copy.straighten = straighten;
        copy.left = left; copy.top = top; copy.right = right; copy.bottom = bottom;
        copy.grayscale = grayscale;
        copy.blackWhite = blackWhite;
        copy.threshold = threshold;
        return copy;
    }

    /** Keeps colour settings but resets geometry, used after the selected region replaces the original. */
    public Edits filtersOnly() {
        Edits filters = new Edits();
        filters.grayscale = grayscale;
        filters.blackWhite = blackWhite;
        filters.threshold = threshold;
        return filters;
    }

    public float angle() { return quarterTurns * 90f + straighten; }

    public boolean fullFrame() { return left <= 0f && top <= 0f && right >= 1f && bottom >= 1f; }

    public boolean geometryChanged() { return quarterTurns != 0 || straighten != 0f || !fullFrame(); }

    public void resetCrop() { left = 0f; top = 0f; right = 1f; bottom = 1f; }

    public void setStraighten(float degrees) {
        straighten = Math.max(-MAX_STRAIGHTEN, Math.min(MAX_STRAIGHTEN, degrees));
    }

    public void setThreshold(int value) { threshold = Math.max(1, Math.min(254, value)); }

    /** Rotates by a quarter turn (positive is clockwise) and moves the crop with the image. */
    public void rotate(int direction) {
        float l = left, t = top, r = right, b = bottom;
        if (direction > 0) {
            left = 1f - b; top = l; right = 1f - t; bottom = r;
            quarterTurns = (quarterTurns + 1) % 4;
        } else {
            left = t; top = 1f - r; right = b; bottom = 1f - l;
            quarterTurns = (quarterTurns + 3) % 4;
        }
    }

    /** Sets the crop in rotated-frame fractions, clamped to the frame and to a minimum size. */
    public void setCrop(float x0, float y0, float x1, float y1) {
        float l = clamp(Math.min(x0, x1)), r = clamp(Math.max(x0, x1));
        float t = clamp(Math.min(y0, y1)), b = clamp(Math.max(y0, y1));
        if (r - l < MIN_CROP) { if (l + MIN_CROP <= 1f) r = l + MIN_CROP; else l = r - MIN_CROP; }
        if (b - t < MIN_CROP) { if (t + MIN_CROP <= 1f) b = t + MIN_CROP; else t = b - MIN_CROP; }
        left = l; top = t; right = r; bottom = b;
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    /** Size of the rotated frame for a source of the given size. */
    public static double[] rotatedSize(int width, int height, float degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.abs(Math.cos(radians)), sin = Math.abs(Math.sin(radians));
        if (cos < 1e-9) cos = 0;
        if (sin < 1e-9) sin = 0;
        return new double[] {width * cos + height * sin, width * sin + height * cos};
    }

    /** Integer frame size for a source, scaled so the longest edge does not exceed {@code maxEdge}. */
    public int[] frameSize(int width, int height, int maxEdge) {
        double[] size = rotatedSize(width, height, angle());
        double scale = scale(width, height, maxEdge);
        return new int[] {Math.max(1, (int) Math.round(size[0] * scale)), Math.max(1, (int) Math.round(size[1] * scale))};
    }

    public double scale(int width, int height, int maxEdge) {
        double[] size = rotatedSize(width, height, angle());
        return Math.min(1d, maxEdge / Math.max(size[0], size[1]));
    }

    /** Crop rectangle {left, top, right, bottom} in pixels of a frame of the given size; never empty. */
    public int[] cropPixels(int frameWidth, int frameHeight) {
        int l = Math.max(0, Math.min(frameWidth - 1, Math.round(left * frameWidth)));
        int t = Math.max(0, Math.min(frameHeight - 1, Math.round(top * frameHeight)));
        int r = Math.max(l + 1, Math.min(frameWidth, Math.round(right * frameWidth)));
        int b = Math.max(t + 1, Math.min(frameHeight, Math.round(bottom * frameHeight)));
        return new int[] {l, t, r, b};
    }

    /** 4x5 Android colour matrix for the selected filter, or null when colours are unchanged. */
    public float[] colorMatrix() {
        if (blackWhite) {
            float k = BW_GAIN, offset = 255f - k * threshold;
            float[] row = {LUMA[0] * k, LUMA[1] * k, LUMA[2] * k, 0, offset};
            return matrix(row);
        }
        if (grayscale) return matrix(new float[] {LUMA[0], LUMA[1], LUMA[2], 0, 0});
        return null;
    }

    private static float[] matrix(float[] row) {
        float[] matrix = new float[20];
        for (int channel = 0; channel < 3; channel++) System.arraycopy(row, 0, matrix, channel * 5, 5);
        matrix[18] = 1f;
        return matrix;
    }

    /** Applies {@link #colorMatrix()} to one opaque RGB pixel, mirroring Android's clamping. */
    public int applyToRgb(int red, int green, int blue) {
        float[] m = colorMatrix();
        if (m == null) return (red << 16) | (green << 8) | blue;
        int[] out = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            float value = m[channel * 5] * red + m[channel * 5 + 1] * green + m[channel * 5 + 2] * blue + m[channel * 5 + 4];
            out[channel] = Math.max(0, Math.min(255, Math.round(value)));
        }
        return (out[0] << 16) | (out[1] << 8) | out[2];
    }

    /** Otsu's method: the threshold maximizing between-class variance of a 256-bin histogram. */
    public static int otsu(int[] histogram) {
        long total = 0, weighted = 0;
        for (int level = 0; level < 256; level++) { total += histogram[level]; weighted += (long) level * histogram[level]; }
        if (total == 0) return 128;
        long background = 0, backgroundSum = 0;
        double best = -1;
        int first = 128, last = 128;
        for (int level = 0; level < 255; level++) {
            background += histogram[level];
            if (background == 0) continue;
            long foreground = total - background;
            if (foreground == 0) break;
            backgroundSum += (long) level * histogram[level];
            double meanBack = (double) backgroundSum / background;
            double meanFore = (double) (weighted - backgroundSum) / foreground;
            double variance = (double) background * foreground * (meanBack - meanFore) * (meanBack - meanFore);
            if (variance > best + 1e-6) { best = variance; first = level; last = level; }
            else if (Math.abs(variance - best) <= 1e-6) last = level;
        }
        // Pixels at or above the threshold become white; split plateaus in the middle.
        return Math.max(1, Math.min(254, (first + last) / 2 + 1));
    }
}
