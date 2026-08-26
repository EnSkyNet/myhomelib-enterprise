package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdkOpdsServerTest {
    private JdkOpdsServer server;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop();
    }

    @Test
    void servesRootAndBoundedAuthorsWithoutJavaFx() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        var status = server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));

        assertThat(status.running()).isTrue();
        assertThat(status.exposedBeyondLocalhost()).isFalse();

        var client = HttpClient.newHttpClient();
        var root = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/opds")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).contains("Автори", "/opds/series", "/opds/genres", "/opds/search");

        var authors = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/opds/authors?limit=1")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(authors.statusCode()).isEqualTo(200);
        assertThat(authors.body()).contains("Автор Один", "2 книг").doesNotContain("Автор Два");
    }

    @Test
    void healthRemainsPublicButCatalogCanRequireBasicAuth() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", port, true, "reader", "secret", false));
        var client = HttpClient.newHttpClient();

        var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);

        var denied = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/opds")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);

        String token = Base64.getEncoder().encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8));
        var allowed = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/opds"))
                .header("Authorization", "Basic " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static final class FakeCatalog implements OpdsCatalogQueryPort {
        @Override public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
            var all = java.util.List.of(new OpdsFacetDto("a1", "Автор Один", 2), new OpdsFacetDto("a2", "Автор Два", 1));
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return new OpdsPage<>(all.subList(from, to), all.size(), offset, limit);
        }
        @Override public OpdsPage<OpdsFacetDto> series(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
        @Override public OpdsPage<OpdsFacetDto> genres(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
        @Override public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) { return new OpdsPage<>(java.util.List.of(), 0, query.offset(), query.limit()); }
        @Override public Optional<OpdsBookDto> book(String bookId) { return Optional.empty(); }
    }
}
