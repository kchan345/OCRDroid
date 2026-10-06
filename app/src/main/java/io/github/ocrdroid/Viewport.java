package io.github.ocrdroid;

/**
 * Zoom and pan state for showing content (an image) in a view. At zoom 1 the content is fitted and
 * centred; zooming keeps the point under the gesture focus fixed, and panning is clamped so the
 * content never leaves gaps that are avoidable. Pure Java so the maths is unit-tested.
 */
public final class Viewport {
    public static final float MAX_ZOOM = 10f;
    /** Upper zoom bound for automatic focusing on a selection; users may zoom further by hand. */
    public static final float MAX_FOCUS_ZOOM = 5f;
    private float contentWidth, contentHeight, viewWidth, viewHeight;
    private float zoom = 1f, centerX, centerY;

    /** Sets the content size; a different size resets zoom and pan. */
    public void setContent(float width, float height) {
        if (width == contentWidth && height == contentHeight) return;
        contentWidth = width;
        contentHeight = height;
        reset();
    }

    public void setView(float width, float height) {
        viewWidth = width;
        viewHeight = height;
        normalize();
    }

    public boolean ready() { return contentWidth > 0 && contentHeight > 0 && viewWidth > 0 && viewHeight > 0; }

    public void reset() {
        zoom = 1f;
        centerX = contentWidth / 2f;
        centerY = contentHeight / 2f;
    }

    public float zoom() { return zoom; }
    public float centerX() { return centerX; }
    public float centerY() { return centerY; }
    public boolean zoomed() { return zoom > 1.001f; }

    public float fit() {
        if (!ready()) return 1f;
        return Math.min(viewWidth / contentWidth, viewHeight / contentHeight);
    }

    /** View pixels per content pixel. */
    public float scale() { return fit() * zoom; }

    /** Screen x of the content's left edge. */
    public float offsetX() { return offset(viewWidth, contentWidth, centerX); }

    public float offsetY() { return offset(viewHeight, contentHeight, centerY); }

    private float offset(float view, float content, float center) {
        float s = scale(), size = content * s;
        if (size <= view) return (view - size) / 2f;
        return Math.max(view - size, Math.min(0f, view / 2f - center * s));
    }

    public float toContentX(float x) { return (x - offsetX()) / scale(); }
    public float toContentY(float y) { return (y - offsetY()) / scale(); }
    public float toViewX(float x) { return offsetX() + x * scale(); }
    public float toViewY(float y) { return offsetY() + y * scale(); }

    /** Multiplies the zoom, keeping the content point under view point (focusX, focusY) in place. */
    public void zoomBy(float factor, float focusX, float focusY) {
        if (!ready()) return;
        float px = toContentX(focusX), py = toContentY(focusY);
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float s = scale();
        centerX = px + (viewWidth / 2f - focusX) / s;
        centerY = py + (viewHeight / 2f - focusY) / s;
        normalize();
    }

    /** Moves the content by a view-pixel delta (positive moves it right/down). */
    public void panBy(float dx, float dy) {
        if (!ready()) return;
        float s = scale();
        centerX -= dx / s;
        centerY -= dy / s;
        normalize();
    }

    /** Sets the state directly, for example while animating; values are clamped. */
    public void set(float zoomValue, float x, float y) {
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoomValue));
        centerX = x;
        centerY = y;
        normalize();
    }

    /**
     * Returns {zoom, centerX, centerY} that centres the content rectangle and makes it fill about
     * {@code fill} of the view, with zoom between 1 and {@link #MAX_FOCUS_ZOOM}.
     */
    public float[] focusTarget(float left, float top, float right, float bottom, float fill) {
        float width = Math.max(1f, right - left), height = Math.max(1f, bottom - top);
        float target = fit() <= 0 ? 1f : Math.min(viewWidth * fill / (width * fit()), viewHeight * fill / (height * fit()));
        target = Math.max(1f, Math.min(MAX_FOCUS_ZOOM, target));
        return new float[] {target, (left + right) / 2f, (top + bottom) / 2f};
    }

    /** Re-derives the centre from the clamped offsets so state always matches what is drawn. */
    private void normalize() {
        if (!ready()) return;
        float s = scale();
        centerX = (viewWidth / 2f - offsetX()) / s;
        centerY = (viewHeight / 2f - offsetY()) / s;
    }
}
