package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdkOpdsServerTest {
    private static final String TLS_PASSWORD_PROPERTY = "myhomelib.opds.tls.keyStorePassword";
    private JdkOpdsServer server;

    @AfterEach
    void cleanup() {
        System.clearProperty(TLS_PASSWORD_PROPERTY);
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
        assertThat(status.baseUrl()).startsWith("http://");
        assertThat(status.healthUrl()).isEqualTo("http://127.0.0.1:" + port + "/health");

        var client = HttpClient.newHttpClient();
        var root = get(client, "http://127.0.0.1:" + port + "/opds");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).contains("Автори", "/opds/series", "/opds/genres", "/opds/search");

        var authors = get(client, "http://127.0.0.1:" + port + "/opds/authors?limit=1");
        assertThat(authors.statusCode()).isEqualTo(200);
        assertThat(authors.body()).contains("Автор Один", "2 книг").doesNotContain("Автор Два");
    }

    @Test
    void rejectsPlainHttpWhenBindingBeyondLoopback() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();

        OpdsServerStatus status = server.start(new OpdsServerSettings("0.0.0.0", port, false, "", "", false));

        assertThat(status.running()).isFalse();
        assertThat(status.exposedBeyondLocalhost()).isTrue();
        assertThat(status.message()).contains("TLS/HTTPS");
        assertThat(server.status().running()).isFalse();
    }

    @Test
    void exposedServerUsesHttpsAndReportsHttpsUrls() throws Exception {
        System.setProperty(TLS_PASSWORD_PROPERTY, "changeit");
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(16, 16, 8, 60, 120, false);
        OpdsServerSettings settings = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                tlsSettings(""), limits);

        OpdsServerStatus status = server.start(settings);

        assertThat(status.running()).isTrue();
        assertThat(status.exposedBeyondLocalhost()).isTrue();
        assertThat(status.baseUrl()).isEqualTo("https://0.0.0.0:" + port + "/opds");
        assertThat(status.healthUrl()).isEqualTo("https://0.0.0.0:" + port + "/health");

        HttpClient client = httpsClient();
        var root = get(client, "https://127.0.0.1:" + port + "/opds");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).contains("MyHomeLib");
    }

    @Test
    void healthIsPublicOnLoopbackButProtectedWhenExposed() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int localPort = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", localPort, true, "reader", "secret", false));

        var localHealth = get(HttpClient.newHttpClient(), "http://127.0.0.1:" + localPort + "/health");
        assertThat(localHealth.statusCode()).isEqualTo(200);

        server.stop();
        int tlsPort = freePort();
        OpdsServerSettings exposed = new OpdsServerSettings("0.0.0.0", tlsPort, true, "reader", "secret", false,
                tlsSettings("changeit"), OpdsSecurityLimits.defaults());
        assertThat(server.start(exposed).running()).isTrue();

        HttpClient client = httpsClient();
        var denied = get(client, "https://127.0.0.1:" + tlsPort + "/health");
        assertThat(denied.statusCode()).isEqualTo(401);

        var allowed = get(client, "https://127.0.0.1:" + tlsPort + "/health", basic("reader", "secret"));
        assertThat(allowed.statusCode()).isEqualTo(200);
    }

    @Test
    void exposedHealthCanBePrivateEvenWhenCatalogHasNoBasicAuth() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsServerSettings settings = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                tlsSettings("changeit"), OpdsSecurityLimits.defaults());
        assertThat(server.start(settings).running()).isTrue();

        HttpClient client = httpsClient();
        assertThat(get(client, "https://127.0.0.1:" + port + "/opds").statusCode()).isEqualTo(200);
        assertThat(get(client, "https://127.0.0.1:" + port + "/health").statusCode()).isEqualTo(403);
    }

    @Test
    void catalogCanRequireBasicAuthWithHashedPassword() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        String hash = OpdsPasswordHash.hash("secret");
        server.start(new OpdsServerSettings("127.0.0.1", port, true, "reader", hash, false));
        var client = HttpClient.newHttpClient();

        var denied = get(client, "http://127.0.0.1:" + port + "/opds");
        assertThat(denied.statusCode()).isEqualTo(401);

        var allowed = get(client, "http://127.0.0.1:" + port + "/opds", basic("reader", "secret"));
        assertThat(allowed.statusCode()).isEqualTo(200);

        var bad = get(client, "http://127.0.0.1:" + port + "/opds", basic("reader", "wrong"));
        assertThat(bad.statusCode()).isEqualTo(401);
    }

    @Test
    void repeatedBadCredentialsTriggerPerClientThrottling() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(16, 16, 3, 60, 120, true);
        OpdsServerSettings settings = new OpdsServerSettings("127.0.0.1", port, true, "reader", "secret", false,
                OpdsTlsSettings.disabled(), limits);
        server.start(settings);
        HttpClient client = HttpClient.newHttpClient();
        String uri = "http://127.0.0.1:" + port + "/opds";

        assertThat(get(client, uri, basic("reader", "bad-1")).statusCode()).isEqualTo(401);
        assertThat(get(client, uri, basic("reader", "bad-2")).statusCode()).isEqualTo(401);
        HttpResponse<String> throttled = get(client, uri, basic("reader", "bad-3"));
        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();

        // A correct password cannot bypass an active block from the same client IP.
        assertThat(get(client, uri, basic("reader", "secret")).statusCode()).isEqualTo(429);
    }

    @Test
    void maxConcurrentRequestsAppliesBackPressureWithoutBreakingNormalRequest() throws Exception {
        BlockingCatalog blocking = new BlockingCatalog();
        server = new JdkOpdsServer(new OpdsCatalogService(blocking), null);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(1, 4, 8, 60, 120, true);
        server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false,
                OpdsTlsSettings.disabled(), limits));
        String uri = "http://127.0.0.1:" + port + "/opds/authors";

        HttpClient firstClient = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> first = firstClient.sendAsync(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(blocking.entered.await(3, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> overloaded = get(HttpClient.newHttpClient(), uri);
        assertThat(overloaded.statusCode()).isEqualTo(503);
        assertThat(overloaded.headers().firstValue("Retry-After")).contains("1");

        blocking.release.countDown();
        assertThat(first.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void failedTlsStartCleansResourcesAndAllowsImmediateRestart() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsTlsSettings missing = new OpdsTlsSettings(true, "/definitely/missing/opds.p12", "PKCS12", "changeit");
        OpdsServerSettings broken = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                missing, OpdsSecurityLimits.defaults());

        OpdsServerStatus failed = server.start(broken);
        assertThat(failed.running()).isFalse();
        assertThat(failed.message()).contains("does not exist");

        OpdsServerStatus restarted = server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));
        assertThat(restarted.running()).isTrue();
        assertThat(get(HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/opds").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String uri, String authorization) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static OpdsTlsSettings tlsSettings(String password) throws Exception {
        Path path = Path.of(JdkOpdsServerTest.class.getResource("/tls/opds-test.p12").toURI());
        return new OpdsTlsSettings(true, path.toString(), "PKCS12", password);
    }

    private static HttpClient httpsClient() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static class FakeCatalog implements OpdsCatalogQueryPort {
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

    private static final class BlockingCatalog extends FakeCatalog {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return super.authors(offset, limit);
        }
    }
}
