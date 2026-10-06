package io.github.ocrdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AppSettings {
    public enum Backend {
        LOCAL_Q4("Q4_K_M"), LOCAL_BF16("BF16"), CLOUD(null);
        public final String precision;
        Backend(String precision) { this.precision = precision; }
        public boolean local() { return precision != null; }
    }

    private static final String KEY_ALIAS = "ocrdroid-cloud-api-key";
    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public Backend backend() {
        try { return Backend.valueOf(preferences.getString("backend", Backend.LOCAL_Q4.name())); }
        catch (IllegalArgumentException unknown) { return Backend.LOCAL_Q4; }
    }

    public void setBackend(Backend backend) { preferences.edit().putString("backend", backend.name()).apply(); }

    public int script() { return preferences.getInt("script", 0); }
    public void setScript(int script) { preferences.edit().putInt("script", script).apply(); }

    public String cloudUrl() { return preferences.getString("cloud_url", ""); }
    public String cloudModel() { return preferences.getString("cloud_model", ""); }

    public boolean cloudConfigured() {
        if (cloudModel().isEmpty()) return false;
        try { CloudOcr.endpoint(cloudUrl()); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Validates and stores the endpoint; an empty key removes any stored key. */
    public void setCloud(String url, String model, String apiKey) throws IOException {
        String endpoint = CloudOcr.endpoint(url);
        if (model.trim().isEmpty()) throw new IllegalArgumentException("Enter the served model name");
        String sealed = "";
        if (!apiKey.isEmpty()) {
            try { sealed = seal(apiKey); }
            catch (GeneralSecurityException error) { throw new IOException("Cannot encrypt the API key", error); }
        }
        if (!preferences.edit().putString("cloud_url", endpoint).putString("cloud_model", model.trim())
                .putString("cloud_key", sealed).commit()) {
            throw new IOException("Cannot save cloud settings");
        }
    }

    public String apiKey() {
        String sealed = preferences.getString("cloud_key", "");
        if (sealed.isEmpty()) return "";
        try { return open(sealed); }
        catch (GeneralSecurityException | IOException | IllegalArgumentException error) {
            Log.w("OCRDroid", "Stored API key cannot be decrypted; enter it again", error);
            return "";
        }
    }

    public boolean hasApiKey() { return !preferences.getString("cloud_key", "").isEmpty(); }

    public CloudOcr.Config cloudConfig() {
        return new CloudOcr.Config(cloudUrl(), cloudModel(), apiKey());
    }

    String storedKeyForTest() { return preferences.getString("cloud_key", ""); }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY_ALIAS, null) instanceof SecretKey existing) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    private static String seal(String value) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] joined = Arrays.copyOf(iv, iv.length + body.length);
        System.arraycopy(body, 0, joined, iv.length, body.length);
        return iv.length + ":" + Base64.getEncoder().encodeToString(joined);
    }

    private static String open(String sealed) throws GeneralSecurityException, IOException {
        int split = sealed.indexOf(':');
        int ivLength = Integer.parseInt(sealed.substring(0, split));
        byte[] joined = Base64.getDecoder().decode(sealed.substring(split + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, joined, 0, ivLength));
        return new String(cipher.doFinal(joined, ivLength, joined.length - ivLength), StandardCharsets.UTF_8);
    }
}
