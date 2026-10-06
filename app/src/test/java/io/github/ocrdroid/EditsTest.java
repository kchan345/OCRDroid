package io.github.ocrdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditsTest {
    private static final float EPS = 1e-5f;

    private static void assertCrop(Edits edits, float l, float t, float r, float b) {
        assertEquals(l, edits.left, EPS);
        assertEquals(t, edits.top, EPS);
        assertEquals(r, edits.right, EPS);
        assertEquals(b, edits.bottom, EPS);
    }

    @Test public void clockwiseRotationMovesTheCropWithTheImage() {
        Edits edits = new Edits();
        edits.setCrop(0.1f, 0.2f, 0.5f, 0.6f);
        edits.rotate(1);
        assertEquals(1, edits.quarterTurns);
        assertCrop(edits, 0.4f, 0.1f, 0.8f, 0.5f);
        edits.rotate(-1);
        assertEquals(0, edits.quarterTurns);
        assertCrop(edits, 0.1f, 0.2f, 0.5f, 0.6f);
    }

    @Test public void fourQuarterTurnsAreIdentity() {
        Edits edits = new Edits();
        edits.setCrop(0.15f, 0.05f, 0.7f, 0.9f);
        for (int i = 0; i < 4; i++) edits.rotate(1);
        assertEquals(0, edits.quarterTurns);
        assertCrop(edits, 0.15f, 0.05f, 0.7f, 0.9f);
        for (int i = 0; i < 4; i++) edits.rotate(-1);
        assertCrop(edits, 0.15f, 0.05f, 0.7f, 0.9f);
        Edits counter = new Edits();
        counter.rotate(-1);
        assertEquals(270f, counter.angle(), EPS);
    }

    @Test public void rotatedFrameIsTheBoundingBox() {
        double[] quarter = Edits.rotatedSize(100, 50, 90);
        assertEquals(50, quarter[0], 1e-6);
        assertEquals(100, quarter[1], 1e-6);
        double[] diagonal = Edits.rotatedSize(100, 50, 45);
        assertEquals(150 * Math.sqrt(0.5), diagonal[0], 1e-6);
        assertEquals(150 * Math.sqrt(0.5), diagonal[1], 1e-6);
    }

    @Test public void frameIsScaledToTheMaximumEdge() {
        Edits edits = new Edits();
        assertArrayEquals(new int[] {2048, 1536}, edits.frameSize(4000, 3000, 2048));
        assertArrayEquals(new int[] {800, 600}, edits.frameSize(800, 600, 2048));
        edits.rotate(1);
        assertArrayEquals(new int[] {1536, 2048}, edits.frameSize(4000, 3000, 2048));
    }

    @Test public void cropPixelsAreNeverEmpty() {
        Edits edits = new Edits();
        assertArrayEquals(new int[] {0, 0, 200, 100}, edits.cropPixels(200, 100));
        edits.setCrop(0.25f, 0.5f, 0.75f, 1f);
        assertArrayEquals(new int[] {50, 50, 150, 100}, edits.cropPixels(200, 100));
        edits.setCrop(1f, 1f, 1f, 1f);
        int[] corner = edits.cropPixels(10, 10);
        assertTrue(corner[2] > corner[0] && corner[3] > corner[1]);
    }

    @Test public void cropIsClampedAndKeepsAMinimumSize() {
        Edits edits = new Edits();
        edits.setCrop(-0.5f, -1f, 2f, 3f);
        assertTrue(edits.fullFrame());
        edits.setCrop(0.9f, 0.9f, 0.92f, 0.95f);
        assertCrop(edits, 0.9f, 0.9f, 0.95f, 0.95f);
        edits.setCrop(0.98f, 0.99f, 1.2f, 1.1f);
        assertCrop(edits, 0.95f, 0.95f, 1f, 1f);
        edits.setCrop(0.6f, 0.7f, 0.2f, 0.1f);
        assertCrop(edits, 0.2f, 0.1f, 0.6f, 0.7f);
    }

    @Test public void geometryAndFilterStateAreTrackedSeparately() {
        Edits edits = new Edits();
        assertFalse(edits.geometryChanged());
        assertNull(edits.colorMatrix());
        edits.setStraighten(90);
        assertEquals(Edits.MAX_STRAIGHTEN, edits.straighten, EPS);
        assertTrue(edits.geometryChanged());
        edits.blackWhite = true;
        edits.setThreshold(500);
        assertEquals(254, edits.threshold);
        Edits filters = edits.filtersOnly();
        assertFalse(filters.geometryChanged());
        assertTrue(filters.blackWhite);
        assertEquals(254, filters.threshold);
        Edits copy = edits.copy();
        assertEquals(edits.straighten, copy.straighten, EPS);
        copy.straighten = 0;
        assertNotEquals(edits.straighten, copy.straighten, EPS);
    }

    @Test public void grayscaleUsesLuminance() {
        Edits edits = new Edits();
        edits.grayscale = true;
        assertEquals(0x4C4C4C, edits.applyToRgb(255, 0, 0));
        assertEquals(0xFFFFFF, edits.applyToRgb(255, 255, 255));
        assertEquals(0, edits.applyToRgb(0, 0, 0));
    }

    @Test public void blackAndWhiteIsBinaryAroundTheThreshold() {
        Edits edits = new Edits();
        edits.grayscale = true;
        edits.blackWhite = true;
        edits.setThreshold(128);
        assertEquals(0xFFFFFF, edits.applyToRgb(129, 129, 129));
        assertEquals(0x000000, edits.applyToRgb(127, 127, 127));
        assertEquals(0xFFFFFF, edits.applyToRgb(255, 255, 255));
        assertEquals(0x000000, edits.applyToRgb(0, 0, 0));
        edits.setThreshold(40);
        assertEquals(0xFFFFFF, edits.applyToRgb(60, 60, 60));
        assertEquals(0x000000, edits.applyToRgb(30, 30, 30));
        // Pure red has luma 76: white at threshold 70, black at threshold 80.
        edits.setThreshold(70);
        assertEquals(0xFFFFFF, edits.applyToRgb(255, 0, 0));
        edits.setThreshold(80);
        assertEquals(0x000000, edits.applyToRgb(255, 0, 0));
    }

    @Test public void otsuSplitsABimodalHistogram() {
        int[] histogram = new int[256];
        histogram[40] = 1000;
        histogram[200] = 3000;
        int threshold = Otsu(histogram);
        assertTrue(String.valueOf(threshold), threshold > 40 && threshold <= 200);
        Edits edits = new Edits();
        edits.blackWhite = true;
        edits.setThreshold(threshold);
        assertEquals(0, edits.applyToRgb(40, 40, 40));
        assertEquals(0xFFFFFF, edits.applyToRgb(200, 200, 200));

        int[] spread = new int[256];
        for (int level = 20; level < 60; level++) spread[level] = 10;
        for (int level = 180; level < 230; level++) spread[level] = 12;
        int split = Otsu(spread);
        assertTrue(String.valueOf(split), split >= 60 && split <= 180);
        assertEquals(128, Edits.otsu(new int[256]));
    }

    private static int Otsu(int[] histogram) { return Edits.otsu(histogram); }
}
