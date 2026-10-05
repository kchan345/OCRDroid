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
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OcrViewModel extends AndroidViewModel {
    public final MutableLiveData<String> status = new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> revision = new MutableLiveData<>(0);
    public final MutableLiveData<String> importedPrecision = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final ModelStore models;
    public Bitmap image;
    public String text = "";
    public List<TextAnchors.Anchor> anchors = new ArrayList<>();
    private volatile boolean cancelled;
    private boolean recognizing;
    private boolean cleared;

    public OcrViewModel(@NonNull Application application) {
        super(application);
        models = new ModelStore(application);
        status.setValue(application.getString(R.string.ready));
    }

    private String message(int id, Object... args) { return getApplication().getString(id, args); }

    private boolean begin(int label, boolean ocr) {
        if (Boolean.TRUE.equals(busy.getValue())) return false;
        cancelled = false;
        recognizing = ocr;
        busy.setValue(true);
        status.setValue(message(label));
        return true;
    }

    private void finish(Runnable apply) {
        main.post(() -> {
            if (cleared) return;
            apply.run();
            recognizing = false;
            busy.setValue(false);
            revision.setValue(revision.getValue() + 1);
        });
    }

    private void fail(Exception error) {
        Log.e("OCRDroid", "Local operation failed", error);
        finish(() -> status.setValue(cancelled ? message(R.string.cancelled) :
            message(R.string.failure, error.getMessage())));
    }

    public void importModel(Uri uri) {
        if (!begin(R.string.importing, false)) return;
        WORKER.execute(() -> {
            try {
                String precision = models.importFolder(uri);
                finish(() -> {
                    importedPrecision.setValue(precision);
                    status.setValue(message(R.string.model_ready, precision));
                });
            } catch (IOException | org.json.JSONException | java.security.NoSuchAlgorithmException |
                     SecurityException | IllegalArgumentException error) { fail(error); }
        });
    }

    public void openImage(Uri uri) {
        if (!begin(R.string.loading_image, false)) return;
        WORKER.execute(() -> {
            try {
                Bitmap normalized = ImageFiles.read(getApplication(), uri);
                ImageFiles.write(normalized, new File(getApplication().getFilesDir(), "page.png"));
                finish(() -> {
                    image = normalized;
                    text = "";
                    anchors = new ArrayList<>();
                    status.setValue(message(R.string.photo_ready));
                });
            } catch (IOException | SecurityException | IllegalArgumentException error) { fail(error); }
        });
    }

    public void recognize(String precision, int script) {
        if (image == null) { status.setValue(message(R.string.need_image)); return; }
        if (!begin(R.string.working, true)) return;
        Bitmap page = image;
        WORKER.execute(() -> {
            try {
                if (cancelled) { finish(() -> status.setValue(message(R.string.cancelled))); return; }
                OcrEngine.nativePrepare();
                if (cancelled) OcrEngine.nativeCancel();
                ModelStore.Bundle bundle = models.selected(precision);
                OcrEngine.Result result = OcrEngine.nativeRecognize(bundle.language.getAbsolutePath(),
                    bundle.vision.getAbsolutePath(),
                    new File(getApplication().getFilesDir(), "page.png").getAbsolutePath(), 2048);
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
                if (result.truncated) completion += "\n" + message(R.string.truncated);
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
            } catch (IOException | org.json.JSONException | IllegalStateException error) { fail(error); }
        });
    }

    public void cancel() {
        if (!recognizing) return;
        cancelled = true;
        OcrEngine.nativeCancel();
        status.setValue(message(R.string.cancel_pending));
    }

    public boolean canCancel() { return recognizing; }

    public void edit(String value, int start, int removed, int inserted) {
        anchors = TextAnchors.edit(anchors, start, removed, inserted);
        text = value;
    }

    public void saveText(Uri uri) {
        if (!begin(R.string.export_text, false)) return;
        String snapshot = text;
        WORKER.execute(() -> {
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
