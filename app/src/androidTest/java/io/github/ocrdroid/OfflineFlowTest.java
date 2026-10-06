package io.github.ocrdroid;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OfflineFlowTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void offlineModelImportInferenceSelectionAndEdit() throws Exception {
        Context context = context();
        new AppSettings(context).setBackend(AppSettings.Backend.LOCAL_Q4);
        boolean internet = context.getPackageManager().checkPermission(Manifest.permission.INTERNET,
            context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        Uri tree = DocumentsContract.buildTreeDocumentUri(context.getPackageName() + ".fixtures", "source");
        ModelStore store = new ModelStore(context);
        assertEquals("Q4_K_M", store.importFolder(tree));
        ModelStore.Bundle bundle = store.selected("Q4_K_M");
        File page = new File(context.getFilesDir(), "test-source/receipt.png");
        Bitmap image = ImageFiles.read(context, Uri.fromFile(page));
        File normalized = new File(context.getFilesDir(), "page.png");
        ImageFiles.write(image, normalized);
        OcrEngine.nativePrepare();
        OcrEngine.Result result = OcrEngine.nativeRecognize(bundle.language.getAbsolutePath(),
            bundle.vision.getAbsolutePath(), normalized.getAbsolutePath(), 256);
        assertTrue(result.text, result.text.contains("Invoice 4729"));
        assertTrue(result.text, result.text.contains("Total 38.50"));
        assertFalse(result.truncated);
        assertTrue(result.peakRssKib > 0 && result.peakRssKib < 3.5 * 1024 * 1024);
        var anchors = TextAnchors.align(result.text, Localizer.locate(image, 0));
        int invoice = result.text.indexOf("Invoice");
        assertFalse(TextAnchors.selected(anchors, invoice, invoice + 7).isEmpty());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                vm.image = image;
                vm.text = result.text;
                vm.anchors = anchors;
                vm.step = OcrViewModel.Step.RESULT;
                vm.editing = true;
                vm.revision.setValue(vm.revision.getValue() + 1);
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.input_screen).getVisibility());
                assertTrue(vm.engineSummary(), vm.engineSummary().contains("Q4_K_M on this device"));
                SelectionEditor editor = activity.findViewById(R.id.editor);
                DocumentView preview = activity.findViewById(R.id.preview);
                editor.setSelection(invoice, invoice + 7);
                assertTrue(preview.highlightedRegionCount() > 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            capture(context, "selection-preview.png");
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                DocumentView preview = activity.findViewById(R.id.preview);
                assertTrue("Selecting text zooms the image to it: " + preview.zoom(), preview.zoom() > 1.05f);
                activity.findViewById(R.id.expand_image).performClick();
                assertEquals(OcrViewModel.Expanded.IMAGE, vm.expanded);
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.text_card).getVisibility());
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.toolbar).getVisibility());
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.save_markdown).getVisibility());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            capture(context, "fullscreen-preview.png");
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals("Back leaves full screen first", OcrViewModel.Expanded.NONE, vm.expanded);
                assertEquals(OcrViewModel.Step.RESULT, vm.step);
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.text_card).getVisibility());
                activity.findViewById(R.id.expand_text).performClick();
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.image_card).getVisibility());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.editor).getVisibility());
                activity.findViewById(R.id.expand_text).performClick();
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.image_card).getVisibility());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.toolbar).getVisibility());
            });
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                SelectionEditor editor = activity.findViewById(R.id.editor);
                DocumentView preview = activity.findViewById(R.id.preview);
                editor.getText().replace(invoice, invoice + 7, "Edited");
                editor.setSelection(invoice, invoice + 6);
                assertEquals(0, preview.highlightedRegionCount());
                assertTrue(vm.text.contains("Edited 4729"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                SelectionEditor editor = activity.findViewById(R.id.editor);
                assertTrue(editor.getText().toString().contains("Edited 4729"));
                int total = editor.getText().toString().indexOf("Total");
                editor.setSelection(total, total + 5);
                DocumentView preview = activity.findViewById(R.id.preview);
                assertTrue("Unedited anchors must survive rotation", preview.highlightedRegionCount() > 0);
                activity.findViewById(R.id.mode_rendered).performClick();
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.rendered).getVisibility());
                assertEquals(android.view.View.GONE, editor.getVisibility());
                assertEquals("Rendered mode has no text selection to highlight", 0, preview.highlightedRegionCount());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(1500);
            capture(context, "rendered-preview.png");
            var instrumentation = InstrumentationRegistry.getInstrumentation();
            var monitor = instrumentation.addMonitor(SettingsActivity.class.getName(), null, false);
            scenario.onActivity(activity -> {
                androidx.drawerlayout.widget.DrawerLayout drawer = activity.findViewById(R.id.drawer);
                drawer.openDrawer(androidx.core.view.GravityCompat.START);
                com.google.android.material.navigation.NavigationView navigation = activity.findViewById(R.id.drawer_menu);
                navigation.getMenu().performIdentifierAction(R.id.nav_settings, 0);
            });
            android.app.Activity settings = instrumentation.waitForMonitorWithTimeout(monitor, 10_000);
            assertNotNull("Hamburger menu must open model settings", settings);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                android.widget.RadioButton q4 = settings.findViewById(R.id.engine_q4);
                assertTrue(q4.isChecked());
                assertTrue(q4.getText().toString(), q4.getText().toString().contains("(imported)"));
                assertNotNull(settings.findViewById(R.id.cloud_url));
                assertNotNull(settings.findViewById(R.id.import_model));
            });
            capture(context, "settings-preview.png");
            instrumentation.runOnMainSync(settings::finish);
            instrumentation.removeMonitor(monitor);
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                activity.findViewById(R.id.mode_edit).performClick();
                assertTrue(vm.editing);
                vm.backToInput();
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.input_screen).getVisibility());
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.result_screen).getVisibility());
            });
            File exportDirectory = new File(context.getCacheDir(), "camera");
            assertTrue(exportDirectory.isDirectory() || exportDirectory.mkdir());
            File exported = new File(exportDirectory, "edited.txt");
            Uri destination = FileProvider.getUriForFile(context, context.getPackageName() + ".files", exported);
            var saved = new java.util.concurrent.CountDownLatch(1);
            scenario.onActivity(activity -> {
                OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                vm.saveText(destination);
                vm.busy.observe(activity, working -> { if (!working) saved.countDown(); });
            });
            assertTrue("Export did not finish", saved.await(15, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(new String(java.nio.file.Files.readAllBytes(exported.toPath()),
                StandardCharsets.UTF_8).contains("Edited 4729"));
        }
        JSONObject evidence = new JSONObject();
        evidence.put("text", result.text);
        evidence.put("seconds", result.seconds);
        evidence.put("peak_rss_kib", result.peakRssKib);
        evidence.put("aligned_tokens", anchors.size());
        evidence.put("internet_permission_for_optional_cloud", internet);
        evidence.put("network_isolated_during_test", true);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "app-evidence.json"))) {
            output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void capture(Context context, String name) throws java.io.IOException {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        ImageFiles.write(screenshot, new File(context.getFilesDir(), name));
        screenshot.recycle();
    }

    @Test public void cancellationIsAnErrorNotAnEmptySuccess() {
        OcrEngine.nativePrepare();
        OcrEngine.nativeCancel();
        try {
            OcrEngine.nativeRecognize("missing", "missing", "missing", 64);
            fail("Cancelled inference must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        } finally {
            OcrEngine.nativePrepare();
        }
    }

    @Test public void manifestRejectsWrongRevisionAndUnsafeNames() throws Exception {
        File source = new File(context().getFilesDir(), "test-source/manifest.json");
        JSONObject manifest;
        try (var input = new java.io.FileInputStream(source)) { manifest = ModelStore.readManifest(input); }
        ModelStore.validate(manifest);
        manifest.put("language_model", "../model.gguf");
        try { ModelStore.validate(manifest); fail("Path traversal must be rejected"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("filenames")); }
        manifest.put("language_model", "model-q4_k_m.gguf");
        manifest.put("model_revision", "unverified");
        try { ModelStore.validate(manifest); fail("Unpinned model must be rejected"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("incompatible")); }
    }
}
