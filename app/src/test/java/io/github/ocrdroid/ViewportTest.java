package io.github.ocrdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class ViewportTest {
    private static final float EPS = 1e-3f;

    private static Viewport viewport() {
        Viewport viewport = new Viewport();
        viewport.setContent(1000, 500);
        viewport.setView(200, 200);
        return viewport;
    }

    @Test public void fitsAndCentresAtZoomOne() {
        Viewport viewport = viewport();
        assertEquals(0.2f, viewport.scale(), EPS);
        assertEquals(0f, viewport.offsetX(), EPS);
        assertEquals(50f, viewport.offsetY(), EPS);
        assertFalse(viewport.zoomed());
        viewport.panBy(100, 100);
        assertEquals("Fitted content cannot be panned", 0f, viewport.offsetX(), EPS);
        assertEquals(50f, viewport.offsetY(), EPS);
    }

    @Test public void zoomKeepsTheFocusPointInPlace() {
        Viewport viewport = viewport();
        float x = viewport.toContentX(150), y = viewport.toContentY(100);
        viewport.zoomBy(3f, 150, 100);
        assertEquals(3f, viewport.zoom(), EPS);
        assertEquals(150f, viewport.toViewX(x), 0.01f);
        assertEquals(100f, viewport.toViewY(y), 0.01f);
        viewport.zoomBy(100f, 0, 0);
        assertEquals(Viewport.MAX_ZOOM, viewport.zoom(), EPS);
        viewport.zoomBy(0.001f, 0, 0);
        assertEquals(1f, viewport.zoom(), EPS);
        assertEquals(50f, viewport.offsetY(), EPS);
    }

    @Test public void panIsClampedToTheContent() {
        Viewport viewport = viewport();
        viewport.zoomBy(4f, 100, 100);
        // Content is 800 x 400 view pixels in a 200 x 200 view.
        viewport.panBy(10_000, 10_000);
        assertEquals(0f, viewport.offsetX(), EPS);
        assertEquals(0f, viewport.offsetY(), EPS);
        viewport.panBy(-10_000, -10_000);
        assertEquals(-600f, viewport.offsetX(), EPS);
        assertEquals(-200f, viewport.offsetY(), EPS);
        viewport.panBy(50, 0);
        assertEquals(-550f, viewport.offsetX(), EPS);
    }

    @Test public void focusCentresARegionWithinLimits() {
        Viewport viewport = viewport();
        float[] target = viewport.focusTarget(400, 200, 500, 250, 0.5f);
        // 100 content pixels at fit 0.2 are 20 view pixels; filling half of 200 needs zoom 5.
        assertEquals(5f, target[0], EPS);
        viewport.set(target[0], target[1], target[2]);
        assertEquals(100f, viewport.toViewX(450), 0.01f);
        assertEquals(100f, viewport.toViewY(225), 0.01f);
        float[] tiny = viewport.focusTarget(10, 10, 11, 11, 0.5f);
        assertEquals(Viewport.MAX_FOCUS_ZOOM, tiny[0], EPS);
        viewport.set(tiny[0], tiny[1], tiny[2]);
        assertEquals("Edge regions are clamped, not centred past the content", 0f, viewport.offsetX(), EPS);
        float[] large = viewport.focusTarget(0, 0, 1000, 500, 0.9f);
        assertEquals(1f, large[0], EPS);
    }

    @Test public void newContentResetsAndUnreadyStateIsSafe() {
        Viewport viewport = viewport();
        viewport.zoomBy(2f, 100, 100);
        viewport.setContent(1000, 500);
        assertTrue("Same content keeps zoom", viewport.zoomed());
        viewport.setContent(400, 400);
        assertFalse(viewport.zoomed());
        Viewport empty = new Viewport();
        empty.zoomBy(2f, 0, 0);
        empty.panBy(5, 5);
        assertFalse(empty.ready());
        assertEquals(1f, empty.zoom(), EPS);
    }
}
