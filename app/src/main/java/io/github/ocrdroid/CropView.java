package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Live preview of {@link Edits}: rotation and colour filters are applied while drawing, and the crop
 * can be dragged by its corners, edges, or body. Two fingers zoom and pan; once zoomed, one finger
 * outside the crop pans; double-tap toggles zoom.
 */
public final class CropView extends View {
    private static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8, MOVE = 16, PAN = 32;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private Bitmap image;
    private Edits edits = new Edits();
    private Runnable changed = () -> {};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF(), crop = new RectF();
    private final float inset, touch, corner, accent;
    private int accentColor = Color.WHITE;
    private int mode;
    private float downX, downY;
    private final float[] start = new float[4];
    private boolean dragging, multiTouch;
    private float lastX, lastY;
    /** Zoom over the unzoomed view: content coordinates are the view's own pixels at zoom 1. */
    private final Viewport viewport = new Viewport();
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;

    public CropView(Context context) {
        super(context);
        inset = Ui.dp(context, 20);
        touch = Ui.dp(context, 28);
        corner = Ui.dp(context, 20);
        accent = Ui.dp(context, 4);
        setBackgroundColor(0xFF161A19);
        setContentDescription(context.getString(R.string.crop_description));
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                viewport.zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                invalidate();
                return true;
            }
        });
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (viewport.zoomed()) viewport.reset();
                else viewport.zoomBy(DOUBLE_TAP_ZOOM, event.getX(), event.getY());
                invalidate();
                return true;
            }
        });
    }

    public float zoom() { return viewport.zoom(); }

    /** Zooms around the view centre (used by tests and accessibility). */
    public void zoomBy(float factor) {
        viewport.zoomBy(factor, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void resetZoom() { viewport.reset(); invalidate(); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        viewport.setContent(width, height);
        viewport.setView(width, height);
    }

    public void setAccent(int color) { accentColor = color; invalidate(); }

    /** Shows {@code bitmap} (usually a downscaled source) with live edits. */
    public void show(Bitmap bitmap, Edits value) {
        if (bitmap != image) viewport.reset();
        image = bitmap;
        edits = value;
        invalidate();
    }

    public void setOnCropChanged(Runnable listener) { changed = listener; }

    private void layoutFrame() {
        if (image == null) { frame.setEmpty(); return; }
        double[] size = Edits.rotatedSize(image.getWidth(), image.getHeight(), edits.angle());
        float width = getWidth() - 2 * inset, height = getHeight() - 2 * inset;
        float fit = (float) Math.min(width / size[0], height / size[1]);
        float w = (float) size[0] * fit, h = (float) size[1] * fit;
        frame.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        frame.set(viewport.toViewX(frame.left), viewport.toViewY(frame.top),
            viewport.toViewX(frame.right), viewport.toViewY(frame.bottom));
        w = frame.width();
        h = frame.height();
        crop.set(frame.left + edits.left * w, frame.top + edits.top * h,
            frame.left + edits.right * w, frame.top + edits.bottom * h);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        layoutFrame();
        if (image == null || frame.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawRect(frame, paint);
        canvas.save();
        canvas.translate(frame.left, frame.top);
        double[] size = Edits.rotatedSize(image.getWidth(), image.getHeight(), edits.angle());
        float fit = frame.width() / (float) size[0];
        canvas.scale(fit, fit);
        PageEditor.draw(canvas, image, edits, (float) size[0], (float) size[1], 1f, edits.colorMatrix());
        canvas.restore();

        paint.setColor(0xB0000000);
        canvas.drawRect(frame.left, frame.top, frame.right, crop.top, paint);
        canvas.drawRect(frame.left, crop.bottom, frame.right, frame.bottom, paint);
        canvas.drawRect(frame.left, crop.top, crop.left, crop.bottom, paint);
        canvas.drawRect(crop.right, crop.top, frame.right, crop.bottom, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(dragging ? 0xCCFFFFFF : 0x66FFFFFF);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        for (int i = 1; i < 3; i++) {
            float x = crop.left + crop.width() * i / 3f, y = crop.top + crop.height() * i / 3f;
            canvas.drawLine(x, crop.top, x, crop.bottom, paint);
            canvas.drawLine(crop.left, y, crop.right, y, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        canvas.drawRect(crop, paint);
        paint.setColor(accentColor);
        paint.setStrokeWidth(accent);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float half = accent / 2f;
        float[][] corners = {{crop.left - half, crop.top - half, 1, 1}, {crop.right + half, crop.top - half, -1, 1},
            {crop.left - half, crop.bottom + half, 1, -1}, {crop.right + half, crop.bottom + half, -1, -1}};
        for (float[] c : corners) {
            canvas.drawLine(c[0], c[1], c[0] + c[2] * corner, c[1], paint);
            canvas.drawLine(c[0], c[1], c[0], c[1] + c[3] * corner, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image == null || !isEnabled()) return false;
        scaler.onTouchEvent(event);
        taps.onTouchEvent(event);
        layoutFrame();
        float x = event.getX(), y = event.getY();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                || (multiTouch && action == MotionEvent.ACTION_MOVE)) {
            if (!multiTouch && mode != 0 && mode != PAN) changed.run();
            multiTouch = true;
            mode = 0;
            dragging = false;
            getParent().requestDisallowInterceptTouchEvent(true);
            int skip = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            float fx = 0, fy = 0;
            int count = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == skip) continue;
                fx += event.getX(i);
                fy += event.getY(i);
                count++;
            }
            fx /= Math.max(1, count);
            fy /= Math.max(1, count);
            if (action == MotionEvent.ACTION_MOVE) viewport.panBy(fx - lastX, fy - lastY);
            lastX = fx;
            lastY = fy;
            invalidate();
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                multiTouch = false;
                mode = hit(x, y);
                lastX = x; lastY = y;
                if (mode == 0) {
                    // Keep the gesture so double-tap and pinch work; outside the crop one finger pans when zoomed.
                    if (viewport.zoomed()) {
                        mode = PAN;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                downX = x; downY = y;
                start[0] = edits.left; start[1] = edits.top; start[2] = edits.right; start[3] = edits.bottom;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return true;
                if (mode == PAN) {
                    viewport.panBy(x - lastX, y - lastY);
                    lastX = x; lastY = y;
                    invalidate();
                    return true;
                }
                float dx = (x - downX) / frame.width(), dy = (y - downY) / frame.height();
                if (mode == MOVE) {
                    float w = start[2] - start[0], h = start[3] - start[1];
                    float l = Math.max(0f, Math.min(1f - w, start[0] + dx));
                    float t = Math.max(0f, Math.min(1f - h, start[1] + dy));
                    edits.setCrop(l, t, l + w, t + h);
                } else {
                    float l = start[0], t = start[1], r = start[2], b = start[3];
                    if ((mode & LEFT) != 0) l = Math.min(start[0] + dx, r - Edits.MIN_CROP);
                    if ((mode & RIGHT) != 0) r = Math.max(start[2] + dx, l + Edits.MIN_CROP);
                    if ((mode & TOP) != 0) t = Math.min(start[1] + dy, b - Edits.MIN_CROP);
                    if ((mode & BOTTOM) != 0) b = Math.max(start[3] + dy, t + Edits.MIN_CROP);
                    edits.setCrop(l, t, r, b);
                }
                changed.run();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                boolean cropped = mode != 0 && mode != PAN;
                if (cropped && action == MotionEvent.ACTION_UP) performClick();
                mode = 0;
                multiTouch = false;
                dragging = false;
                if (cropped) changed.run();
                invalidate();
                return true;
            }
            default -> { return true; }
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private int hit(float x, float y) {
        boolean nearLeft = Math.abs(x - crop.left) < touch, nearRight = Math.abs(x - crop.right) < touch;
        boolean nearTop = Math.abs(y - crop.top) < touch, nearBottom = Math.abs(y - crop.bottom) < touch;
        boolean withinX = x > crop.left - touch && x < crop.right + touch;
        boolean withinY = y > crop.top - touch && y < crop.bottom + touch;
        if (!withinX || !withinY) return 0;
        int sides = 0;
        if (nearLeft) sides |= LEFT; else if (nearRight) sides |= RIGHT;
        if (nearTop) sides |= TOP; else if (nearBottom) sides |= BOTTOM;
        if (sides != 0) return sides;
        return crop.contains(x, y) ? MOVE : 0;
    }
}
