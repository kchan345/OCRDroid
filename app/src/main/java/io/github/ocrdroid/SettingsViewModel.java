package io.github.ocrdroid;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.util.List;

public final class SettingsViewModel extends AndroidViewModel {
    public final MutableLiveData<String> status = new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> revision = new MutableLiveData<>(0);
    /** Model id reported by the server when the user left the model field blank. */
    public final MutableLiveData<String> suggestedModel = new MutableLiveData<>();
    final AppSettings settings;
    final ModelStore models;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean cleared;

    public SettingsViewModel(@NonNull Application application) {
        super(application);
        settings = new AppSettings(application);
        models = new ModelStore(application);
        status.setValue(application.getString(R.string.settings_intro));
    }

    private String message(int id, Object... args) { return getApplication().getString(id, args); }

    private boolean begin(int label) {
        if (Boolean.TRUE.equals(busy.getValue())) return false;
        busy.setValue(true);
        status.setValue(message(label));
        return true;
    }

    private void finish(String result) {
        main.post(() -> {
            if (cleared) return;
            status.setValue(result);
            busy.setValue(false);
            revision.setValue(revision.getValue() + 1);
        });
    }

    public void importModel(Uri uri) {
        if (!begin(R.string.importing)) return;
        Work.MODELS.execute(() -> {
            try {
                String precision = models.importFolder(uri);
                if (settings.backend().local()) {
                    settings.setBackend("BF16".equals(precision) ? AppSettings.Backend.LOCAL_BF16 : AppSettings.Backend.LOCAL_Q4);
                }
                finish(message(R.string.model_ready, precision));
            } catch (IOException | org.json.JSONException | java.security.NoSuchAlgorithmException |
                     SecurityException | IllegalArgumentException error) {
                Log.e("OCRDroid", "Model import failed", error);
                finish(message(R.string.failure, error.getMessage()));
            }
        });
    }

    public void removeModel(String precision) {
        if (!begin(R.string.removing)) return;
        Work.MODELS.execute(() -> {
            try {
                models.remove(precision);
                finish(message(R.string.model_removed, precision));
            } catch (IOException error) {
                finish(message(R.string.failure, error.getMessage()));
            }
        });
    }

    public void saveCloud(String url, String model, String key) {
        try {
            settings.setCloud(url, model, key);
            status.setValue(message(R.string.cloud_saved, settings.cloudUrl()));
        } catch (IOException | IllegalArgumentException error) {
            status.setValue(message(R.string.failure, error.getMessage()));
        }
        revision.setValue(revision.getValue() + 1);
    }

    public void testConnection(String url, String model, String key) {
        String endpoint;
        try { endpoint = CloudOcr.endpoint(url); }
        catch (IllegalArgumentException error) { status.setValue(message(R.string.failure, error.getMessage())); return; }
        if (!begin(R.string.testing_connection)) return;
        Work.IO.execute(() -> {
            try {
                List<String> served = new CloudOcr().models(new CloudOcr.Config(endpoint, model.trim(), key));
                String names = served.isEmpty() ? "-" : String.join(", ", served);
                if (model.trim().isEmpty() && !served.isEmpty()) main.post(() -> suggestedModel.setValue(served.get(0)));
                finish(model.trim().isEmpty() || served.contains(model.trim())
                    ? message(R.string.connection_ok, names) : message(R.string.connection_model_missing, model.trim(), names));
            } catch (IOException error) {
                finish(message(R.string.connection_failed, error.getMessage()));
            }
        });
    }

    @Override protected void onCleared() { cleared = true; }
}
