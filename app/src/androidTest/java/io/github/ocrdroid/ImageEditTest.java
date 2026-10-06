package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Preprocessing editor: rendering maths on real bitmaps and the keep-original / region-only flows. */
@RunWith(AndroidJUnit4.class)
public class ImageEditTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static Bitmap receipt() throws Exception {
        return ImageFiles.read(context(), Uri.fromFile(new File(context().getFilesDir(), "test-source/receipt.png")));
    }

    @Test public void renderRotatesCropsAndBinarizes() throws Exception {
        Bitmap source = receipt();
        Edits edits = new Edits();
        edits.rotate(1);
        edits.setCrop(0f, 0.1f, 1f, 0.6f);
        edits.blackWhite = true;
        edits.setThreshold(PageEditor.autoThreshold(source, edits));
        assertTrue(edits.threshold >= 1 && edits.threshold <= 254);

        int[] frame = edits.frameSize(source.getWidth(), source.getHeight(), PageEditor.MAX_EDGE);
        assertTrue("A quarter turn swaps width and height", (frame[0] > frame[1]) == (source.getHeight() > source.getWidth()));
        int[] crop = edits.cropPixels(frame[0], frame[1]);
        Bitmap page = PageEditor.render(source, edits, true, true, PageEditor.MAX_EDGE);
        assertEquals(crop[2] - crop[0], page.getWidth());
        assertEquals(crop[3] - crop[1], page.getHeight());
        Rect region = PageEditor.region(source, edits, PageEditor.MAX_EDGE);
        assertEquals(page.getWidth(), region.width());
        assertEquals(page.getHeight(), region.height());

        int[] pixels = new int[page.getWidth() * page.getHeight()];
        page.getPixels(pixels, 0, page.getWidth(), 0, 0, page.getWidth(), page.getHeight());
        int binary = 0, dark = 0;
        for (int pixel : pixels) {
            int red = Color.red(pixel);
            if (red == 0 || red == 255) binary++;
            if (red == 0) dark++;
            assertEquals(red, Color.green(pixel));
            assertEquals(red, Color.blue(pixel));
        }
        assertTrue("Black-and-white output must be binary", binary >= pixels.length * 0.99);
        assertTrue("Text must survive thresholding", dark > 0 && dark < pixels.length / 2);

        Edits gray = new Edits();
        gray.grayscale = true;
        Bitmap grayPage = PageEditor.render(source, gray, true, true, PageEditor.MAX_EDGE);
        int sample = grayPage.getPixel(grayPage.getWidth() / 2, grayPage.getHeight() / 3);
        assertEquals(Color.red(sample), Color.green(sample));
        assertEquals(Color.green(sample), Color.blue(sample));
    }

    @Test public void keepOriginalAndRegionOnlyFlows() throws Exception {
        Context context = context();
        AppSettings settings = new AppSettings(context);
        // BF16 is never imported in CI, so OCR stops immediately after the page is prepared.
        settings.setBackend(AppSettings.Backend.LOCAL_BF16);
        settings.setKeepOriginal(true);
        File receipt = new File(context.getFilesDir(), "test-source/receipt.png");
        File stored = new File(context.getFilesDir(), "source.png");
        File capture = new File(new File(context.getCacheDir(), "camera"), "capture.jpg");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> model(activity).openImage(Uri.fromFile(receipt)));
            await(scenario, vm -> vm.step == OcrViewModel.Step.ADJUST && idle(vm));
            int[] sourceSize = new int[2];
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.adjust_screen).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.input_screen).getVisibility());
                assertTrue(vm.keepOriginal);
                sourceSize[0] = vm.source.getWidth();
                sourceSize[1] = vm.source.getHeight();
                vm.edits.setCrop(0.08f, 0.05f, 0.92f, 0.55f);
                activity.findViewById(R.id.black_white).performClick();
                assertTrue(vm.edits.blackWhite);
                assertTrue(activity.findViewById(R.id.threshold).isEnabled());
                activity.findViewById(R.id.threshold_auto).performClick();
                activity.findViewById(R.id.rotate_right).performClick();
                activity.findViewById(R.id.rotate_left).performClick();
                assertEquals(0, vm.edits.quarterTurns);
                assertEquals(0.08f, vm.edits.left, 1e-4f);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(500);
            capture(context, "adjust-preview.png");
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                CropView crop = activity.findViewById(R.id.crop_view);
                crop.zoomBy(2.5f);
                assertEquals(2.5f, crop.zoom(), 1e-3f);
                assertEquals("Zooming does not change the crop", 0.08f, vm.edits.left, 1e-4f);
                activity.findViewById(R.id.expand_crop).performClick();
                assertEquals(View.GONE, activity.findViewById(R.id.adjust_tools).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.adjust_bar).getVisibility());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(300);
            capture(context, "adjust-fullscreen-preview.png");
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.expand_crop).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.adjust_tools).getVisibility());
                CropView crop = activity.findViewById(R.id.crop_view);
                crop.resetZoom();
                assertEquals(1f, crop.zoom(), 1e-3f);
            });

            scenario.onActivity(activity -> activity.findViewById(R.id.apply_edits).performClick());
            await(scenario, vm -> vm.step == OcrViewModel.Step.RESULT && idle(vm));
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.result_screen).getVisibility());
                assertNotNull("The original stays visible", vm.original);
                assertNotNull("The OCR region is outlined", vm.region);
                assertEquals(sourceSize[0], vm.source.getWidth());
                assertTrue(vm.image.getWidth() < vm.original.getWidth());
                assertEquals(vm.image.getWidth(), vm.region.width());
                assertTrue(vm.status.getValue(), vm.status.getValue().contains("BF16"));
            });
            Bitmap kept = ImageFiles.read(context, Uri.fromFile(stored));
            assertEquals(sourceSize[0], kept.getWidth());
            assertEquals(sourceSize[1], kept.getHeight());
            assertTrue(new File(context.getFilesDir(), "page.png").isFile());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "region-preview.png");

            assertTrue(capture.getParentFile().isDirectory() || capture.getParentFile().mkdirs());
            ImageFiles.write(kept, capture);
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                activity.findViewById(R.id.adjust).performClick();
                assertEquals(OcrViewModel.Step.ADJUST, vm.step);
                assertEquals("Edits are kept for re-adjusting", 0.08f, vm.edits.left, 1e-4f);
                activity.findViewById(R.id.keep_region).performClick();
                assertFalse(vm.keepOriginal);
                activity.findViewById(R.id.apply_edits).performClick();
            });
            await(scenario, vm -> vm.step == OcrViewModel.Step.RESULT && idle(vm));
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                assertNull(vm.original);
                assertNull(vm.region);
                assertTrue("Only filters remain after cropping", vm.edits.fullFrame());
                assertTrue(vm.edits.blackWhite);
                assertEquals(vm.image.getWidth(), vm.source.getWidth());
                assertEquals(vm.image.getHeight(), vm.source.getHeight());
            });
            Bitmap region = ImageFiles.read(context, Uri.fromFile(stored));
            assertTrue("The stored photo is replaced by the region", region.getWidth() < sourceSize[0]);
            assertFalse("The camera capture is deleted", capture.exists());
            scenario.onActivity(activity -> {
                OcrViewModel vm = model(activity);
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(OcrViewModel.Step.ADJUST, vm.step);
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(OcrViewModel.Step.INPUT, vm.step);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.input_screen).getVisibility());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "input-preview.png");
        } finally {
            settings.setKeepOriginal(true);
            settings.setBackend(AppSettings.Backend.LOCAL_Q4);
        }
    }

    private static OcrViewModel model(MainActivity activity) {
        return new ViewModelProvider(activity).get(OcrViewModel.class);
    }

    private static boolean idle(OcrViewModel vm) { return !Boolean.TRUE.equals(vm.busy.getValue()); }

    private static void await(ActivityScenario<MainActivity> scenario, Predicate<OcrViewModel> done) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        AtomicBoolean reached = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            scenario.onActivity(activity -> reached.set(done.test(model(activity))));
            if (reached.get()) return;
            Thread.sleep(100);
        }
        String[] status = new String[1];
        scenario.onActivity(activity -> status[0] = model(activity).status.getValue());
        fail("Timed out; status: " + status[0]);
    }

    private static void capture(Context context, String name) throws java.io.IOException {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        ImageFiles.write(screenshot, new File(context.getFilesDir(), name));
        screenshot.recycle();
    }
}
