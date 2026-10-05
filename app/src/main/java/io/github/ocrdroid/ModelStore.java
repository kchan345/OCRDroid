package io.github.ocrdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class ModelStore {
    public static final class Bundle {
        public final String precision;
        public final File language, vision;
        Bundle(String precision, File language, File vision) {
            this.precision = precision; this.language = language; this.vision = vision;
        }
    }
    private final Context context;
    private final SharedPreferences preferences;

    public ModelStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("models", Context.MODE_PRIVATE);
    }

    public Bundle selected(String precision) throws IOException, JSONException {
        String directory = preferences.getString(precision, null);
        if (directory == null) throw new IOException(context.getString(R.string.no_model));
        File root = new File(context.getFilesDir(), directory);
        JSONObject manifest;
        try (InputStream input = new FileInputStream(new File(root, "manifest.json"))) {
            manifest = readManifest(input);
        }
        validate(manifest);
        File language = new File(root, manifest.getString("language_model"));
        File vision = new File(root, manifest.getString("vision_model"));
        JSONObject files = manifest.getJSONObject("files");
        if (language.length() != files.getJSONObject(language.getName()).getLong("bytes") ||
            vision.length() != files.getJSONObject(vision.getName()).getLong("bytes")) {
            throw new IOException("Imported weights are missing or damaged; reimport the model folder");
        }
        return new Bundle(precision, language, vision);
    }

    public String importFolder(Uri uri) throws IOException, JSONException, NoSuchAlgorithmException {
        DocumentFile tree = DocumentFile.fromTreeUri(context, uri);
        if (tree == null || !tree.isDirectory()) throw new IOException("Select a model directory");
        DocumentFile manifestFile = tree.findFile("manifest.json");
        if (manifestFile == null) throw new IOException("Folder needs manifest.json from the model export workflow");
        JSONObject manifest;
        try (InputStream input = context.getContentResolver().openInputStream(manifestFile.getUri())) {
            if (input == null) throw new IOException("Cannot open model manifest");
            manifest = readManifest(input);
        }
        validate(manifest);
        JSONObject files = manifest.getJSONObject("files");
        String[] names = {manifest.getString("language_model"), manifest.getString("vision_model")};
        long required = 64L * 1024 * 1024;
        for (String name : names) required += files.getJSONObject(name).getLong("bytes");
        if (context.getFilesDir().getUsableSpace() < required) {
            throw new IOException("Not enough free internal storage to import this model");
        }
        File stage = new File(context.getFilesDir(), "bundle-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("Cannot create model import directory");
        try {
            for (String name : names) {
                DocumentFile source = tree.findFile(name);
                if (source == null || !source.isFile()) throw new IOException("Missing " + name);
                JSONObject info = files.getJSONObject(name);
                copyVerified(source.getUri(), new File(stage, name), info.getLong("bytes"), info.getString("sha256"));
            }
            try (FileOutputStream output = new FileOutputStream(new File(stage, "manifest.json"))) {
                output.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException | JSONException | NoSuchAlgorithmException error) {
            try { removeBundle(stage); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        String precision = manifest.getString("quantization");
        String previous = preferences.getString(precision, null);
        if (!preferences.edit().putString(precision, stage.getName()).commit()) {
            removeBundle(stage);
            throw new IOException("Cannot persist imported model selection");
        }
        if (previous != null) removeBundle(new File(context.getFilesDir(), previous));
        return precision;
    }

    private void copyVerified(Uri source, File target, long expectedSize, String expectedHash)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Cannot read " + target.getName());
            byte[] buffer = new byte[1024 * 1024];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > expectedSize) throw new IOException("Weight file exceeds manifest size");
                output.write(buffer, 0, read);
                hash.update(buffer, 0, read);
            }
            if (count != expectedSize) throw new IOException("Incomplete weight file");
            StringBuilder actual = new StringBuilder();
            for (byte value : hash.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!actual.toString().equalsIgnoreCase(expectedHash)) throw new IOException("Weight checksum mismatch");
            output.getFD().sync();
        }
        try (FileInputStream input = new FileInputStream(target)) {
            byte[] magic = input.readNBytes(4);
            if (!java.util.Arrays.equals(magic, new byte[]{'G', 'G', 'U', 'F'})) {
                throw new IOException("Weights must use GGUF, not safetensors");
            }
        }
    }

    static JSONObject readManifest(InputStream input) throws IOException, JSONException {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw new IOException("Model manifest is too large");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static void validate(JSONObject manifest) throws JSONException, IOException {
        String precision = manifest.getString("quantization");
        if (manifest.getInt("format_version") != 1 ||
            !"ATH-MaaS/OvisOCR2".equals(manifest.getString("model_repository")) ||
            !BuildConfig.MODEL_REVISION.equals(manifest.getString("model_revision")) ||
            !BuildConfig.LLAMA_REVISION.equals(manifest.getString("llama_revision")) ||
            !manifest.getBoolean("exclude_mtp") ||
            !("Q4_K_M".equals(precision) || "BF16".equals(precision))) {
            throw new IOException("Model manifest is incompatible with this app version");
        }
        String language = "BF16".equals(precision) ? "model-bf16.gguf" : "model-q4_k_m.gguf";
        if (!language.equals(manifest.getString("language_model")) ||
            !"mmproj-f16.gguf".equals(manifest.getString("vision_model"))) {
            throw new IOException("Unexpected model filenames");
        }
        JSONObject files = manifest.getJSONObject("files");
        for (String name : new String[]{language, "mmproj-f16.gguf"}) {
            JSONObject info = files.getJSONObject(name);
            long size = info.getLong("bytes");
            if (size < 1024 || size > 3L * 1024 * 1024 * 1024 ||
                !info.getString("sha256").matches("[a-fA-F0-9]{64}")) {
                throw new IOException("Invalid weight size or checksum");
            }
        }
    }

    private void removeBundle(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect old model folder");
        for (File file : files) if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
        if (!directory.delete()) throw new IOException("Cannot remove old model folder");
    }
}
