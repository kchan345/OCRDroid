package io.github.ocrdroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Client for an OpenAI-compatible chat completions server, such as vLLM serving OvisOCR2. */
public final class CloudOcr {
    public static final String INSTRUCTION =
        "\nExtract all readable content from the image in natural human reading order and output "
        + "the result as a single Markdown document. For charts or images, represent them using an "
        + "HTML image tag: <img src=\"images/bbox_{left}_{top}_{right}_{bottom}.jpg\" />, where left, "
        + "top, right, bottom are bounding box coordinates scaled to [0, 1000). Format formulas as "
        + "LaTeX. Format tables as HTML: <table>...</table>. Transcribe all other text as standard "
        + "Markdown. Preserve the original text without translation or paraphrasing.";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    public static final class Config {
        public final String baseUrl, model, apiKey;
        public Config(String baseUrl, String model, String apiKey) {
            this.baseUrl = baseUrl; this.model = model; this.apiKey = apiKey;
        }
    }

    public static final class Reply {
        public final String text;
        public final boolean truncated;
        public final int tokens;
        Reply(String text, boolean truncated, int tokens) {
            this.text = text; this.truncated = truncated; this.tokens = tokens;
        }
    }

    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    /** Normalizes a server address to its OpenAI API base, adding /v1 when no path is given. */
    public static String endpoint(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter the server URL, for example http://192.168.1.20:8000/v1");
        URI uri;
        try { uri = new URI(value); }
        catch (URISyntaxException error) { throw new IllegalArgumentException("Invalid server URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Server URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Server URL needs a host and no credentials, query, or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length());
        if (path.isEmpty()) path = "/v1";
        return scheme + "://" + uri.getRawAuthority() + path;
    }

    /** True when the endpoint sends data without TLS to a non-loopback host. */
    public static boolean unencrypted(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        return "http".equals(uri.getScheme()) && !("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
    }

    static JSONObject request(String model, String imageDataUrl, int maxTokens) throws JSONException {
        JSONArray content = new JSONArray()
            .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageDataUrl)))
            .put(new JSONObject().put("type", "text").put("text", INSTRUCTION));
        return new JSONObject()
            .put("model", model)
            .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
            .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
    }

    static Reply parse(String body) throws JSONException, IOException {
        JSONObject json = new JSONObject(body);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("Server returned no completion choices");
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        if (message == null) throw new IOException("Server response has no message");
        Object content = message.opt("content");
        StringBuilder text = new StringBuilder();
        if (content instanceof String value) {
            text.append(value);
        } else if (content instanceof JSONArray parts) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) text.append(part.optString("text"));
            }
        }
        String result = stripThinking(text.toString());
        if (result.isEmpty()) throw new IOException("Server returned empty OCR text");
        JSONObject usage = json.optJSONObject("usage");
        int tokens = usage == null ? 0 : usage.optInt("completion_tokens", 0);
        return new Reply(result, "length".equals(choice.optString("finish_reason")), tokens);
    }

    static String stripThinking(String text) {
        String value = text.trim();
        if (value.startsWith("<think>")) {
            int end = value.indexOf("</think>");
            if (end >= 0) value = value.substring(end + "</think>".length()).trim();
        }
        return value;
    }

    static String errorMessage(int code, String body) {
        String detail = body == null ? "" : body.trim();
        try {
            JSONObject json = new JSONObject(detail);
            Object error = json.opt("error");
            if (error instanceof JSONObject object) detail = object.optString("message", detail);
            else if (error instanceof String value) detail = value;
            else if (json.has("message")) detail = json.optString("message");
            else if (json.has("detail")) detail = json.opt("detail").toString();
        } catch (JSONException notJson) {
            // Keep the raw body.
        }
        if (detail.length() > 300) detail = detail.substring(0, 300) + "...";
        return "Server returned HTTP " + code + (detail.isEmpty() ? "" : ": " + detail);
    }

    public OcrEngine.Result recognize(Config config, byte[] jpeg, int maxTokens) throws IOException {
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        byte[] body;
        try { body = request(config.model, dataUrl, maxTokens).toString().getBytes(StandardCharsets.UTF_8); }
        catch (JSONException error) { throw new IOException("Cannot build request", error); }
        long started = System.nanoTime();
        String response = exchange("POST", config, "/chat/completions", body, 600_000);
        try {
            Reply reply = parse(response);
            return new OcrEngine.Result(reply.text.getBytes(StandardCharsets.UTF_8), reply.truncated, reply.tokens,
                (System.nanoTime() - started) / 1e9, 0);
        } catch (JSONException error) {
            throw new IOException("Server response is not an OpenAI chat completion", error);
        }
    }

    public List<String> models(Config config) throws IOException {
        String response = exchange("GET", config, "/models", null, 20_000);
        try {
            JSONArray data = new JSONObject(response).getJSONArray("data");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) ids.add(data.getJSONObject(i).getString("id"));
            return ids;
        } catch (JSONException error) {
            throw new IOException("Server response is not an OpenAI model list", error);
        }
    }

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private String exchange(String method, Config config, String path, byte[] body, int readTimeout) throws IOException {
        URL url = new URL(endpoint(config.baseUrl) + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        active = connection;
        try {
            if (cancelled) throw new IOException("cancelled");
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(readTimeout);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!config.apiKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream error = connection.getErrorStream();
                throw new IOException(errorMessage(code, error == null ? "" : read(error)));
            }
            try (InputStream input = connection.getInputStream()) { return read(input); }
        } catch (IOException error) {
            if (cancelled) throw new IOException("cancelled", error);
            throw error;
        } finally {
            active = null;
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        try (input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Server response is too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
