package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CloudBackendTest {
    private static final String COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
        + "\"message\":{\"role\":\"assistant\",\"content\":\"# Receipt\\n\\nInvoice 4729\\n\\nTotal 38.50\"},"
        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":14}}";

    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @After public void restoreDefaults() {
        new AppSettings(context()).setBackend(AppSettings.Backend.LOCAL_Q4);
    }

    private static byte[] jpeg() {
        Bitmap page = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(page.compress(Bitmap.CompressFormat.JPEG, 90, output));
        page.recycle();
        return output.toByteArray();
    }

    @Test public void chatCompletionRequestAndResponse() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(request -> new FakeOpenAiServer.Response(200, COMPLETION))) {
            OcrEngine.Result result = new CloudOcr().recognize(
                new CloudOcr.Config(server.url(), "ATH-MaaS/OvisOCR2", "test-key"), jpeg(), 8192);
            assertEquals("# Receipt\n\nInvoice 4729\n\nTotal 38.50", result.text);
            assertEquals(14, result.tokens);
            assertFalse(result.truncated);
            FakeOpenAiServer.Request request = server.requests.get(0);
            assertEquals("POST", request.method);
            assertEquals("/v1/chat/completions", request.path);
            assertEquals("Bearer test-key", request.headers.get("authorization"));
            JSONObject body = new JSONObject(request.body);
            assertEquals("ATH-MaaS/OvisOCR2", body.getString("model"));
            assertEquals(8192, body.getInt("max_tokens"));
            String url = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                .getJSONObject(0).getJSONObject("image_url").getString("url");
            assertTrue(url.startsWith("data:image/jpeg;base64,/9j/"));
        }
    }

    @Test public void serverErrorsAreReportedWithDetail() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(request -> new FakeOpenAiServer.Response(401,
                "{\"error\":{\"message\":\"Invalid API key\"}}"))) {
            try {
                new CloudOcr().recognize(new CloudOcr.Config(server.url() + "/v1", "m", ""), jpeg(), 64);
                fail("HTTP 401 must fail");
            } catch (IOException expected) {
                assertEquals("Server returned HTTP 401: Invalid API key", expected.getMessage());
            }
            assertNull("No key configured means no Authorization header", server.requests.get(0).headers.get("authorization"));
        }
    }

    @Test public void modelListAndEncryptedKeyStorage() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer(request -> new FakeOpenAiServer.Response(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"ATH-MaaS/OvisOCR2\",\"object\":\"model\"}]}"))) {
            AppSettings settings = new AppSettings(context());
            settings.setCloud(server.url(), " ATH-MaaS/OvisOCR2 ", "sk-secret-value");
            assertEquals(server.url() + "/v1", settings.cloudUrl());
            assertEquals("ATH-MaaS/OvisOCR2", settings.cloudModel());
            assertTrue(settings.cloudConfigured());
            assertEquals("sk-secret-value", settings.apiKey());
            assertFalse("API key must not be stored in plain text", settings.storedKeyForTest().contains("sk-secret"));
            assertEquals(java.util.List.of("ATH-MaaS/OvisOCR2"), new CloudOcr().models(settings.cloudConfig()));
            assertEquals("/v1/models", server.requests.get(0).path);
            assertEquals("Bearer sk-secret-value", server.requests.get(0).headers.get("authorization"));
            settings.setCloud(server.url(), "ATH-MaaS/OvisOCR2", "");
            assertFalse(settings.hasApiKey());
            assertEquals("", settings.apiKey());
        }
    }

    @Test public void cancellationAbortsAWaitingRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (FakeOpenAiServer server = new FakeOpenAiServer(request -> {
                received.countDown();
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return new FakeOpenAiServer.Response(200, COMPLETION);
            })) {
            CloudOcr client = new CloudOcr();
            new Thread(() -> {
                try { if (received.await(10, TimeUnit.SECONDS)) client.cancel(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }).start();
            long started = System.nanoTime();
            try {
                client.recognize(new CloudOcr.Config(server.url(), "m", ""), jpeg(), 64);
                fail("Cancelled request must fail");
            } catch (IOException expected) {
                assertEquals("cancelled", expected.getMessage());
            }
            assertTrue("Cancellation must not wait for the server", System.nanoTime() - started < 12_000_000_000L);
        }
    }

    @Test public void cloudEngineDrivesTheScanWorkflowWithHighlights() throws Exception {
        Context context = context();
        Bitmap page = ImageFiles.read(context, Uri.fromFile(new File(context.getFilesDir(), "test-source/receipt.png")));
        try (FakeOpenAiServer server = new FakeOpenAiServer(request -> new FakeOpenAiServer.Response(200, COMPLETION))) {
            AppSettings settings = new AppSettings(context);
            settings.setCloud(server.url(), "ATH-MaaS/OvisOCR2", "");
            settings.setBackend(AppSettings.Backend.CLOUD);
            settings.setScript(0);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                CountDownLatch done = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                    assertTrue(vm.engineSummary(), vm.engineSummary().contains("cloud ATH-MaaS/OvisOCR2"));
                    vm.image = page;
                    vm.step = OcrViewModel.Step.RESULT;
                    vm.revision.setValue(vm.revision.getValue() + 1);
                    vm.recognize();
                    assertTrue(vm.busy.getValue());
                    vm.busy.observe(activity, working -> { if (!working) done.countDown(); });
                });
                assertTrue("Cloud OCR did not finish", done.await(120, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    OcrViewModel vm = new ViewModelProvider(activity).get(OcrViewModel.class);
                    assertTrue(vm.status.getValue(), vm.text.contains("Invoice 4729"));
                    assertFalse("ML Kit should localize cloud text", vm.anchors.isEmpty());
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.rendered).getVisibility());
                    assertEquals(View.GONE, activity.findViewById(R.id.editor).getVisibility());
                    activity.findViewById(R.id.mode_edit).performClick();
                    SelectionEditor editor = activity.findViewById(R.id.editor);
                    assertEquals(View.VISIBLE, editor.getVisibility());
                    int invoice = vm.text.indexOf("Invoice");
                    editor.setSelection(invoice, invoice + 7);
                    DocumentView preview = activity.findViewById(R.id.preview);
                    assertTrue(preview.highlightedRegionCount() > 0);
                });
            }
            assertEquals(1, server.requests.size());
        }
    }
}
