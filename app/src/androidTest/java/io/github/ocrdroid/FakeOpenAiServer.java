package io.github.ocrdroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Minimal loopback HTTP server that stands in for a vLLM OpenAI-compatible endpoint. */
final class FakeOpenAiServer implements AutoCloseable {
    static final class Request {
        String method, path, body;
        final Map<String, String> headers = new HashMap<>();
    }

    static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ServerSocket socket;
    private final Function<Request, Response> handler;

    FakeOpenAiServer(Function<Request, Response> handler) throws IOException {
        this.handler = handler;
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-openai");
        thread.setDaemon(true);
        thread.start();
    }

    String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(20_000);
                InputStream input = client.getInputStream();
                Request request = new Request();
                String[] start = line(input).split(" ");
                request.method = start[0];
                request.path = start[1];
                for (String header = line(input); !header.isEmpty(); header = line(input)) {
                    int colon = header.indexOf(':');
                    request.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
                }
                int length = Integer.parseInt(request.headers.getOrDefault("content-length", "0"));
                request.body = new String(input.readNBytes(length), StandardCharsets.UTF_8);
                requests.add(request);
                Response response = handler.apply(request);
                byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
                OutputStream output = client.getOutputStream();
                output.write(("HTTP/1.1 " + response.code + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                output.write(body);
                output.flush();
            } catch (IOException | RuntimeException error) {
                if (socket.isClosed()) return;
            }
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') if (value != '\r') bytes.write(value);
        return bytes.toString(StandardCharsets.ISO_8859_1.name());
    }

    @Override public void close() throws IOException { socket.close(); }
}
