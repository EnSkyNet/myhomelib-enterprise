package com.myhomelibcorp.infrastructure.sync.webdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** JDK HttpClient implementation with bounded response reads. */
public final class JdkWebDavHttpClient implements WebDavHttpClient {
    private final HttpClient client;
    private final Duration timeout;
    private final int maxResponseBytes;

    public JdkWebDavHttpClient(HttpClient client, Duration timeout, int maxResponseBytes) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes is too small");
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public Response exchange(String method, URI uri, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers != null) headers.forEach(builder::header);
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] bytes = readBounded(in, maxResponseBytes);
            return new Response(response.statusCode(), response.headers().map(), bytes);
        }
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            total += read;
            if (total > maxBytes) throw new IOException("WebDAV response exceeds configured limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
