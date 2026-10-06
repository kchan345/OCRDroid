package io.github.ocrdroid;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class CloudOcrTest {
    @Test public void endpointNormalization() {
        assertEquals("http://10.0.0.5:8000/v1", CloudOcr.endpoint("http://10.0.0.5:8000"));
        assertEquals("http://10.0.0.5:8000/v1", CloudOcr.endpoint("  HTTP://10.0.0.5:8000/  "));
        assertEquals("https://ocr.example.com/v1", CloudOcr.endpoint("https://ocr.example.com/v1/"));
        assertEquals("https://ocr.example.com/v1", CloudOcr.endpoint("https://ocr.example.com/v1/chat/completions"));
        assertEquals("https://gw.example.com/team/openai/v1", CloudOcr.endpoint("https://gw.example.com/team/openai/v1"));
        for (String invalid : new String[]{"", "ftp://host/v1", "host:8000", "http://user:pw@host/v1",
                "http://host/v1?key=1", "http:///v1"}) {
            try { CloudOcr.endpoint(invalid); fail("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    @Test public void cleartextDetection() {
        assertTrue(CloudOcr.unencrypted("http://192.168.1.20:8000/v1"));
        assertFalse(CloudOcr.unencrypted("https://192.168.1.20:8000/v1"));
        assertFalse(CloudOcr.unencrypted("http://127.0.0.1:8000/v1"));
        assertFalse(CloudOcr.unencrypted("http://localhost:8000/v1"));
    }

    @Test public void requestMatchesOpenAiVisionChatFormat() throws Exception {
        JSONObject request = CloudOcr.request("ATH-MaaS/OvisOCR2", "data:image/jpeg;base64,AAAA", 8192);
        assertEquals("ATH-MaaS/OvisOCR2", request.getString("model"));
        assertEquals(8192, request.getInt("max_tokens"));
        assertEquals(0, request.getInt("temperature"));
        assertFalse(request.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
        JSONObject message = request.getJSONArray("messages").getJSONObject(0);
        assertEquals("user", message.getString("role"));
        JSONObject image = message.getJSONArray("content").getJSONObject(0);
        assertEquals("image_url", image.getString("type"));
        assertEquals("data:image/jpeg;base64,AAAA", image.getJSONObject("image_url").getString("url"));
        JSONObject text = message.getJSONArray("content").getJSONObject(1);
        assertEquals(CloudOcr.INSTRUCTION, text.getString("text"));
        assertTrue(CloudOcr.INSTRUCTION.startsWith("\nExtract all readable content"));
    }

    @Test public void parsesCompletionsAndStripsEmptyThinking() throws Exception {
        CloudOcr.Reply reply = CloudOcr.parse("{\"choices\":[{\"message\":{\"content\":\"<think>\\n\\n</think>\\n\\n# Invoice 4729\"},"
            + "\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":12}}");
        assertEquals("# Invoice 4729", reply.text);
        assertEquals(12, reply.tokens);
        assertFalse(reply.truncated);
        CloudOcr.Reply parts = CloudOcr.parse("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"A\"},"
            + "{\"type\":\"text\",\"text\":\"B\"}]},\"finish_reason\":\"length\"}]}");
        assertEquals("AB", parts.text);
        assertTrue(parts.truncated);
        assertEquals(0, parts.tokens);
    }

    @Test public void rejectsEmptyOrMalformedCompletions() throws Exception {
        for (String body : new String[]{"{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}", "{\"choices\":[{}]}"}) {
            try { CloudOcr.parse(body); fail("Accepted " + body); }
            catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    @Test public void errorMessagesUseServerDetail() {
        assertEquals("Server returned HTTP 401: Invalid API key",
            CloudOcr.errorMessage(401, "{\"error\":{\"message\":\"Invalid API key\"}}"));
        assertEquals("Server returned HTTP 404: The model `x` does not exist.",
            CloudOcr.errorMessage(404, "{\"object\":\"error\",\"message\":\"The model `x` does not exist.\",\"code\":404}"));
        assertEquals("Server returned HTTP 502: Bad gateway", CloudOcr.errorMessage(502, "Bad gateway"));
        assertEquals("Server returned HTTP 500", CloudOcr.errorMessage(500, ""));
        assertTrue(CloudOcr.errorMessage(500, "x".repeat(1000)).length() < 360);
    }
}
