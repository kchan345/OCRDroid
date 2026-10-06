package io.github.ocrdroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.Collections;
import java.util.List;

/**
 * Shows the page with highlights, with pinch zoom, panning, and double-tap zoom. When the original
 * image is kept, it shows the whole original with the OCR region outlined; highlight boxes are in
 * OCR-page coordinates and are offset into it.
 */
public final class DocumentView extends View {
    /** Fraction of the view a focused selection should fill. */
    private static final float FOCUS_FILL = 0.6f;
    private static final float DOUBLE_TAP_ZOOM = 3f;
    private Bitmap image;
    private Rect region;
    private List<TextAnchors.Box> boxes = Collections.emptyList();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final Viewport viewport = new Viewport();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private ValueAnimator animator;
    /** Content rectangle to focus once the view has a size; selections can arrive before layout. */
    private float[] pendingFocus;
    private int accent = 0xFF006A60;

    public DocumentView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.preview_description));
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                stopAnimation();
                viewport.zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { stopAnimation(); return true; }

            @Override public boolean onScroll(MotionEvent first, MotionEvent event, float dx, float dy) {
                viewport.panBy(-dx, -dy);
                invalidate();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (viewport.zoomed()) animateTo(1f, viewport.centerX(), viewport.centerY());
                else animateTo(DOUBLE_TAP_ZOOM, viewport.toContentX(event.getX()), viewport.toContentY(event.getY()));
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }
        });
    }

    public void setAccent(int color) { accent = color; invalidate(); }

    public void setImage(Bitmap bitmap) { setImage(bitmap, null); }

    /** {@code area} is the OCR region within {@code bitmap}, or null when the bitmap is the OCR page. */
    public void setImage(Bitmap bitmap, Rect area) {
        if (bitmap == image && java.util.Objects.equals(area, region)) return;
        stopAnimation();
        image = bitmap;
        region = area;
        boxes = Collections.emptyList();
        pendingFocus = null;
        if (bitmap != null) viewport.setContent(bitmap.getWidth(), bitmap.getHeight());
        viewport.reset();
        invalidate();
    }

    public void highlight(List<TextAnchors.Box> selection) { boxes = selection; invalidate(); }
    int highlightedRegionCount() { return boxes.size(); }

    /** Current zoom; 1 means the whole image is fitted. */
    public float zoom() { return viewport.zoom(); }

    public void resetZoom() { pendingFocus = null; animateTo(1f, viewport.centerX(), viewport.centerY()); }

    /**
     * Animates zoom and pan so the highlighted boxes are centred and fill part of the view. The user
     * can keep zooming and panning afterwards. Returns the target zoom, or 0 when nothing changes.
     */
    public float focusOn(List<TextAnchors.Box> selection) {
        if (image == null || selection.isEmpty()) return 0f;
        float ox = region == null ? 0 : region.left, oy = region == null ? 0 : region.top;
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (TextAnchors.Box item : selection) {
            left = Math.min(left, item.left + ox);
            top = Math.min(top, item.top + oy);
            right = Math.max(right, item.right + ox);
            bottom = Math.max(bottom, item.bottom + oy);
        }
        if (!viewport.ready()) {
            pendingFocus = new float[] {left, top, right, bottom};
            return 0f;
        }
        pendingFocus = null;
        float[] target = viewport.focusTarget(left, top, right, bottom, FOCUS_FILL);
        animateTo(target[0], target[1], target[2]);
        return target[0];
    }

    private void animateTo(float zoom, float x, float y) {
        stopAnimation();
        if (!viewport.ready() || !isShown()) {
            viewport.set(zoom, x, y);
            invalidate();
            return;
        }
        float startZoom = viewport.zoom(), startX = viewport.centerX(), startY = viewport.centerY();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            viewport.set(startZoom + (zoom - startZoom) * t, startX + (x - startX) * t, startY + (y - startY) * t);
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        viewport.setView(width, height);
        if (pendingFocus != null && viewport.ready()) {
            float[] area = pendingFocus;
            pendingFocus = null;
            float[] target = viewport.focusTarget(area[0], area[1], area[2], area[3], FOCUS_FILL);
            viewport.set(target[0], target[1], target[2]);
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image == null) return false;
        scaler.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (viewport.zoomed() || event.getPointerCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null) return;
        float scale = viewport.scale();
        canvas.save();
        canvas.translate(viewport.offsetX(), viewport.offsetY());
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(image, 0, 0, paint);
        float ox = 0, oy = 0;
        if (region != null) {
            ox = region.left;
            oy = region.top;
            paint.setColor(0x88000000);
            canvas.drawRect(0, 0, image.getWidth(), region.top, paint);
            canvas.drawRect(0, region.bottom, image.getWidth(), image.getHeight(), paint);
            canvas.drawRect(0, region.top, region.left, region.bottom, paint);
            canvas.drawRect(region.right, region.top, image.getWidth(), region.bottom, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(accent);
            paint.setStrokeWidth(2.5f / scale);
            canvas.drawRect(region, paint);
        }
        for (TextAnchors.Box item : boxes) {
            box.set(item.left + ox, item.top + oy, item.right + ox, item.bottom + oy);
            paint.setColor(0x66FFD43B);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(box, paint);
            paint.setColor(0xFFAD6700);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 / scale);
            canvas.drawRect(box, paint);
        }
        canvas.restore();
    }
}
