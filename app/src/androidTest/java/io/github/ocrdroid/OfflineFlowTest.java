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
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.getPackageManager().checkPermission(Manifest.permission.INTERNET, context.getPackageName()));
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
                vm.revision.setValue(vm.revision.getValue() + 1);
                SelectionEditor editor = activity.findViewById(R.id.editor);
                DocumentView preview = activity.findViewById(R.id.preview);
                editor.setSelection(invoice, invoice + 7);
                assertTrue(preview.highlightedRegionCount() > 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            ImageFiles.write(screenshot, new File(context.getFilesDir(), "selection-preview.png"));
            screenshot.recycle();
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
        evidence.put("internet_permission", false);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "app-evidence.json"))) {
            output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
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
