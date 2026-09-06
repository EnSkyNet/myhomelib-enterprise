package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.opds.OpdsPasswordHash;
import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsBookQuery;
import com.myhomelibcorp.application.opds.OpdsCatalogService;
import com.myhomelibcorp.application.opds.OpdsFacetDto;
import com.myhomelibcorp.application.opds.OpdsPage;
import com.myhomelibcorp.application.opds.OpdsSecurityLimits;
import com.myhomelibcorp.application.opds.OpdsServerSettings;
import com.myhomelibcorp.application.opds.OpdsSettingsService;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.opds.JdkOpdsCertificateManager;
import com.myhomelibcorp.opds.JdkOpdsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsJourneyE2ETest {

    @TempDir
    Path tempDir;

    private JdkOpdsServer server;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop();
        System.clearProperty("myhomelib.dataDir");
        System.clearProperty("myhomelib.opds.tls.keyStorePassword");
    }

    @Test
    void loopbackOpdsEnforcesBasicAuthAndServesRealAtomFeed() throws Exception {
        int port = freePort();
        server = new JdkOpdsServer(new OpdsCatalogService(catalogWithOneBook()), null);
        String hash = OpdsPasswordHash.hash("e2e-pass");
        server.start(new OpdsServerSettings(
                "127.0.0.1", port, true, "e2e", hash, false));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> unauthorized = get(client, "http://127.0.0.1:" + port + "/opds", null);
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        HttpResponse<String> feed = get(client, "http://127.0.0.1:" + port + "/opds", basic("e2e", "e2e-pass"));
        assertThat(feed.statusCode()).isEqualTo(200);
        assertThat(feed.headers().firstValue("Content-Type").orElse("")).contains("application/atom+xml");
        assertThat(feed.body()).contains("MyHomeLib").contains("/opds/authors").contains("/opds/search");

        HttpResponse<String> authors = get(client, "http://127.0.0.1:" + port + "/opds/authors", basic("e2e", "e2e-pass"));
        assertThat(authors.statusCode()).isEqualTo(200);
        assertThat(authors.body()).contains("E2E Author");
    }

    @Test
    void managedCertificateAndEncryptedSettingsRestartHttpsWithoutManualSecret() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("opds-data").toString());
        JdkOpdsCertificateManager certificateManager = new JdkOpdsCertificateManager();
        var managed = certificateManager.generateSelfSigned("127.0.0.1");
        assertThat(managed.certificate().fingerprintSha256()).isNotBlank();

        InMemorySettings raw = new InMemorySettings();
        OpdsSettingsService settings = new OpdsSettingsService(raw);
        int port = freePort();
        settings.save(new OpdsServerSettings(
                "127.0.0.1", port, false, "", "", false,
                managed.tls(), OpdsSecurityLimits.defaults()));

        String persistedSecret = raw.values.get("opds.tls.keyStorePassword");
        assertThat(persistedSecret).startsWith("mhlenc:v1:");
        assertThat(persistedSecret).doesNotContain(managed.tls().keyStorePassword());
        assertThat(System.getProperty("myhomelib.opds.tls.keyStorePassword")).isNull();

        OpdsServerSettings reloaded = settings.load();
        server = new JdkOpdsServer(new OpdsCatalogService(catalogWithOneBook()), null);
        var status = server.start(reloaded);
        assertThat(status.running()).isTrue();
        assertThat(status.baseUrl()).startsWith("https://");

        HttpClient client = HttpClient.newBuilder().sslContext(trusting(managed.tls())).build();
        HttpResponse<String> response = get(client, "https://127.0.0.1:" + port + "/opds", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("MyHomeLib");
    }

    private static HttpResponse<String> get(HttpClient client, String uri, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String username, String password) {
        String value = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + value;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SSLContext trusting(com.myhomelibcorp.application.opds.OpdsTlsSettings tls) throws Exception {
        char[] password = tls.keyStorePassword().toCharArray();
        try {
            KeyStore source = KeyStore.getInstance(tls.keyStoreType());
            try (InputStream in = Files.newInputStream(Path.of(tls.keyStorePath()))) {
                source.load(in, password);
            }
            String alias = source.aliases().nextElement();
            Certificate certificate = source.getCertificate(alias);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("opds-e2e", certificate);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return ssl;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static OpdsCatalogQueryPort catalogWithOneBook() {
        return new OpdsCatalogQueryPort() {
            @Override
            public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
                return new OpdsPage<>(java.util.List.of(new OpdsFacetDto("a1", "E2E Author", 1)), 1, offset, limit);
            }

            @Override
            public OpdsPage<OpdsFacetDto> series(int offset, int limit) {
                return new OpdsPage<>(java.util.List.of(), 0, offset, limit);
            }

            @Override
            public OpdsPage<OpdsFacetDto> genres(int offset, int limit) {
                return new OpdsPage<>(java.util.List.of(), 0, offset, limit);
            }

            @Override
            public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) {
                return new OpdsPage<>(java.util.List.of(), 0, query.offset(), query.limit());
            }

            @Override
            public Optional<OpdsBookDto> book(String bookId) {
                return Optional.empty();
            }
        };
    }

    private static final class InMemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
        @Override public void replaceByPrefix(String prefix, Map<String, String> replacement) {
            values.keySet().removeIf(key -> key.startsWith(prefix));
            replacement.forEach((key, value) -> { if (value != null) values.put(key, value); });
        }
    }
}
