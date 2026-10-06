package io.github.ocrdroid;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class OcrViewModel extends AndroidViewModel {
    static final int MAX_TOKENS = 2048;
    static final int CLOUD_MAX_TOKENS = 8192;
    public final MutableLiveData<String> status = new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> revision = new MutableLiveData<>(0);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ModelStore models;
    private final AppSettings settings;
    public enum Step { INPUT, ADJUST, RESULT }
    /** Panel shown full screen: the image (adjust or result step) or the text output. */
    public enum Expanded { NONE, IMAGE, TEXT }

    /** Editable base image: the decoded original, or only the selected region once the original is discarded. */
    public Bitmap source;
    /** Downscaled {@link #source} for the live editor preview. */
    public Bitmap sourcePreview;
    public Edits edits = new Edits();
    /** OCR input after rotation, crop, and colour filters; highlight boxes use its coordinates. */
    public Bitmap image;
    /** Rotated full original shown beside the output when it is kept; null shows {@link #image}. */
    public Bitmap original;
    /** OCR region within {@link #original}, or null. */
    public android.graphics.Rect region;
    public boolean keepOriginal;
    public String text = "";
    public List<TextAnchors.Anchor> anchors = new ArrayList<>();
    public Step step = Step.INPUT;
    /** Output panel mode: plain-text editor when true, rendered Markdown when false. */
    public boolean editing;
    public Expanded expanded = Expanded.NONE;
    private volatile boolean cancelled;
    private volatile CloudOcr cloud;
    private boolean recognizing;
    private boolean cleared;

    public OcrViewModel(@NonNull Application application) {
        super(application);
        models = new ModelStore(application);
        settings = new AppSettings(application);
        keepOriginal = settings.keepOriginal();
        status.setValue(application.getString(R.string.ready));
    }

    private File file(String name) { return new File(getApplication().getFilesDir(), name); }

    private String message(int id, Object... args) { return getApplication().getString(id, args); }

    public String engineSummary() {
        AppSettings.Backend backend = settings.backend();
        if (backend.local()) {
            return models.has(backend.precision) ? message(R.string.engine_local, backend.precision)
                : message(R.string.engine_local_missing, backend.precision);
        }
        if (!settings.cloudConfigured()) return message(R.string.engine_cloud_missing);
        return message(R.string.engine_cloud, settings.cloudModel(), URI.create(settings.cloudUrl()).getAuthority());
    }

    private boolean begin(String label, boolean ocr) {
        if (Boolean.TRUE.equals(busy.getValue())) return false;
        cancelled = false;
        recognizing = ocr;
        busy.setValue(true);
        status.setValue(label);
        return true;
    }

    private void finish(Runnable apply) {
        main.post(() -> {
            if (cleared) return;
            apply.run();
            recognizing = false;
            cloud = null;
            busy.setValue(false);
            revision.setValue(revision.getValue() + 1);
        });
    }

    private void fail(Exception error) {
        Log.e("OCRDroid", "Operation failed", error);
        finish(() -> status.setValue(cancelled ? message(R.string.cancelled) :
            message(R.string.failure, error.getMessage())));
    }

    /** Step 1 to 2: decodes the photo and opens the adjust screen. */
    public void openImage(Uri uri) {
        if (!begin(message(R.string.loading_image), false)) return;
        Work.MODELS.execute(() -> {
            try {
                Bitmap normalized = ImageFiles.read(getApplication(), uri);
                ImageFiles.write(normalized, file("source.png"));
                Bitmap small = PageEditor.preview(normalized, 1280);
                finish(() -> {
                    source = normalized;
                    sourcePreview = small;
                    edits = new Edits();
                    image = null;
                    original = null;
                    region = null;
                    text = "";
                    anchors = new ArrayList<>();
                    step = Step.ADJUST;
                    expanded = Expanded.NONE;
                    status.setValue(message(R.string.adjust_ready));
                });
            } catch (IOException | SecurityException | IllegalArgumentException error) { fail(error); }
        });
    }

    public void setKeepOriginal(boolean keep) {
        keepOriginal = keep;
        settings.setKeepOriginal(keep);
    }

    /** Suggests a black-and-white threshold for the current region from the preview image. */
    public int autoThreshold() {
        return sourcePreview == null ? 128 : PageEditor.autoThreshold(sourcePreview, edits);
    }

    /**
     * Step 2 to 3: renders the OCR page from the edits, stores it, and starts OCR. When only the
     * selected region is kept, it replaces the stored original and the camera capture is deleted.
     */
    public void applyEdits() {
        if (source == null) { status.setValue(message(R.string.need_image)); return; }
        if (!begin(message(R.string.preparing_page), false)) return;
        Bitmap base = source;
        Edits snapshot = edits.copy();
        boolean keep = keepOriginal;
        Work.MODELS.execute(() -> {
            try {
                Bitmap page = PageEditor.render(base, snapshot, true, true, PageEditor.MAX_EDGE);
                ImageFiles.write(page, file("page.png"));
                Bitmap full = null, nextSource = base, nextPreview = null;
                android.graphics.Rect area = null;
                Edits nextEdits = snapshot;
                if (keep) {
                    boolean unchanged = !snapshot.geometryChanged() && snapshot.colorMatrix() == null;
                    full = unchanged ? page : PageEditor.render(base, snapshot, false, false, PageEditor.MAX_EDGE);
                    if (!snapshot.fullFrame()) area = PageEditor.region(base, snapshot, PageEditor.MAX_EDGE);
                } else {
                    nextSource = snapshot.geometryChanged()
                        ? PageEditor.render(base, snapshot, true, false, PageEditor.MAX_EDGE) : base;
                    ImageFiles.write(nextSource, file("source.png"));
                    File capture = new File(new File(getApplication().getCacheDir(), "camera"), "capture.jpg");
                    if (capture.exists() && !capture.delete()) Log.w("OCRDroid", "Cannot delete camera capture");
                    nextEdits = snapshot.filtersOnly();
                    nextPreview = PageEditor.preview(nextSource, 1280);
                }
                Bitmap shown = full, editable = nextSource, editablePreview = nextPreview;
                android.graphics.Rect shownRegion = area;
                Edits finalEdits = nextEdits;
                main.post(() -> {
                    if (cleared) return;
                    image = page;
                    original = shown;
                    region = shownRegion;
                    source = editable;
                    if (editablePreview != null) sourcePreview = editablePreview;
                    edits = finalEdits;
                    text = "";
                    anchors = new ArrayList<>();
                    step = Step.RESULT;
                    expanded = Expanded.NONE;
                    editing = false;
                    busy.setValue(false);
                    revision.setValue(revision.getValue() + 1);
                    recognize();
                });
            } catch (IOException | IllegalArgumentException | OutOfMemoryError error) {
                fail(error instanceof OutOfMemoryError ? new IOException("Not enough memory to prepare the image") : (Exception) error);
            }
        });
    }

    /** Step 3 back to 2: re-opens the editor; a running OCR job is cancelled. */
    public void adjust() {
        if (source == null) return;
        cancel();
        step = Step.ADJUST;
        expanded = Expanded.NONE;
        revision.setValue(revision.getValue() + 1);
    }

    public void recognize() {
        if (image == null) { status.setValue(message(R.string.need_image)); return; }
        AppSettings.Backend backend = settings.backend();
        if (backend.local() && !models.has(backend.precision)) {
            status.setValue(message(R.string.engine_local_missing, backend.precision));
            return;
        }
        if (!backend.local() && !settings.cloudConfigured()) {
            status.setValue(message(R.string.engine_cloud_missing));
            return;
        }
        String label = backend.local() ? message(R.string.working, backend.precision)
            : message(R.string.working_cloud, settings.cloudModel());
        if (!begin(label, true)) return;
        Bitmap page = image;
        int script = settings.script();
        CloudOcr.Config config = backend.local() ? null : settings.cloudConfig();
        CloudOcr client = config == null ? null : new CloudOcr();
        cloud = client;
        Work.MODELS.execute(() -> {
            try {
                if (cancelled) { finish(() -> status.setValue(message(R.string.cancelled))); return; }
                OcrEngine.Result result;
                if (client == null) {
                    OcrEngine.nativePrepare();
                    if (cancelled) OcrEngine.nativeCancel();
                    ModelStore.Bundle bundle = models.selected(backend.precision);
                    result = OcrEngine.nativeRecognize(bundle.language.getAbsolutePath(),
                        bundle.vision.getAbsolutePath(),
                        file("page.png").getAbsolutePath(), MAX_TOKENS);
                } else {
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    if (!page.compress(Bitmap.CompressFormat.JPEG, 95, jpeg)) throw new IOException("Cannot encode photo");
                    result = client.recognize(config, jpeg.toByteArray(), CLOUD_MAX_TOKENS);
                }
                if (cancelled) { finish(() -> status.setValue(message(R.string.cancelled))); return; }
                status.postValue(message(R.string.localizing));
                List<TextAnchors.Anchor> aligned;
                String completion;
                try {
                    aligned = TextAnchors.align(result.text, Localizer.locate(page, script));
                    completion = message(R.string.finished, result.seconds, result.tokens, aligned.size());
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException |
                         InterruptedException | IllegalArgumentException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    Log.e("OCRDroid", "Text localization failed", error);
                    aligned = new ArrayList<>();
                    completion = message(R.string.grounding_failure, error.getMessage());
                }
                if (result.truncated) completion += "\n" + message(R.string.truncated, client == null ? MAX_TOKENS : CLOUD_MAX_TOKENS);
                List<TextAnchors.Anchor> finalAnchors = aligned;
                String finalCompletion = completion;
                finish(() -> {
                    if (cancelled) {
                        status.setValue(message(R.string.cancelled));
                    } else {
                        text = result.text;
                        anchors = finalAnchors;
                        status.setValue(finalCompletion);
                    }
                });
            } catch (IOException | org.json.JSONException | IllegalStateException | IllegalArgumentException error) {
                fail(error);
            }
        });
    }

    public void cancel() {
        if (!recognizing) return;
        cancelled = true;
        CloudOcr client = cloud;
        if (client != null) client.cancel();
        else OcrEngine.nativeCancel();
        status.setValue(message(R.string.cancel_pending));
    }

    public boolean canCancel() { return recognizing; }

    /** Returns to step 1; a running OCR job is cancelled because its result would be discarded. */
    public void backToInput() {
        cancel();
        step = Step.INPUT;
        expanded = Expanded.NONE;
        revision.setValue(revision.getValue() + 1);
    }

    public void edit(String value, int start, int removed, int inserted) {
        anchors = TextAnchors.edit(anchors, start, removed, inserted);
        text = value;
    }

    public void saveText(Uri uri) {
        if (!begin(message(R.string.export_text), false)) return;
        String snapshot = text;
        Work.IO.execute(() -> {
            try (OutputStream output = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Cannot open destination");
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
            } catch (IOException | SecurityException error) { fail(error); return; }
            finish(() -> status.setValue(message(R.string.saved)));
        });
    }

    @Override protected void onCleared() {
        cleared = true;
        cancel();
    }
}
