package io.github.ocrdroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

/** Renders {@link Edits} onto bitmaps; the editor preview uses the same transform and colour filter. */
final class PageEditor {
    static final int MAX_EDGE = 2048;

    private PageEditor() {}

    /**
     * Renders the source through rotation, optionally the crop, and optionally the colour filter.
     * Exposed corners from straightening are white so they read as paper.
     */
    static Bitmap render(Bitmap source, Edits edits, boolean crop, boolean filters, int maxEdge) {
        int[] frame = edits.frameSize(source.getWidth(), source.getHeight(), maxEdge);
        float scale = (float) edits.scale(source.getWidth(), source.getHeight(), maxEdge);
        int[] area = crop ? edits.cropPixels(frame[0], frame[1]) : new int[] {0, 0, frame[0], frame[1]};
        Bitmap output = Bitmap.createBitmap(area[2] - area[0], area[3] - area[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        canvas.translate(-area[0], -area[1]);
        draw(canvas, source, edits, frame[0], frame[1], scale, filters ? edits.colorMatrix() : null);
        return output;
    }

    /** Draws the source into a frame of {@code frameWidth x frameHeight} at the origin. */
    static void draw(Canvas canvas, Bitmap source, Edits edits, float frameWidth, float frameHeight, float scale,
                     float[] colorMatrix) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (colorMatrix != null) paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        canvas.save();
        canvas.translate(frameWidth / 2f, frameHeight / 2f);
        canvas.rotate(edits.angle());
        canvas.scale(scale, scale);
        canvas.translate(-source.getWidth() / 2f, -source.getHeight() / 2f);
        canvas.drawBitmap(source, 0, 0, paint);
        canvas.restore();
    }

    /** Crop rectangle in pixels of the full rendered frame, matching {@link #render} output coordinates. */
    static Rect region(Bitmap source, Edits edits, int maxEdge) {
        int[] frame = edits.frameSize(source.getWidth(), source.getHeight(), maxEdge);
        int[] area = edits.cropPixels(frame[0], frame[1]);
        return new Rect(area[0], area[1], area[2], area[3]);
    }

    /** Suggests a black-and-white threshold for the selected region using Otsu's method. */
    static int autoThreshold(Bitmap source, Edits edits) {
        Edits gray = edits.copy();
        gray.blackWhite = false;
        gray.grayscale = true;
        Bitmap sample = render(source, gray, true, true, 384);
        int[] pixels = new int[sample.getWidth() * sample.getHeight()];
        sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
        sample.recycle();
        int[] histogram = new int[256];
        for (int pixel : pixels) histogram[Color.red(pixel)]++;
        return Edits.otsu(histogram);
    }

    /** Downscaled copy for interactive preview; returns the source itself when already small. */
    static Bitmap preview(Bitmap source, int maxEdge) {
        float scale = Math.min(1f, (float) maxEdge / Math.max(source.getWidth(), source.getHeight()));
        if (scale >= 1f) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
            Math.max(1, Math.round(source.getHeight() * scale)), true);
    }
}
