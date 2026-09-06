package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsManagedTlsIntegrationTest {
    @TempDir Path tempDir;
    private JdkOpdsServer server;

    @BeforeAll
    static void stableEncryptionKey() {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterEach
    void cleanup() {
        if (server != null) server.stop();
        System.clearProperty("myhomelib.dataDir");
        System.clearProperty("myhomelib.opds.tls.keyStorePassword");
    }

    @Test
    void generatedCertificatePersistsEncryptedSecretAndStartsHttpsWithoutManualRuntimeSecret() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        JdkOpdsCertificateManager certificateManager = new JdkOpdsCertificateManager();
        var managed = certificateManager.generateSelfSigned("127.0.0.1");

        InMemorySettings rawSettings = new InMemorySettings();
        OpdsSettingsService settingsService = new OpdsSettingsService(rawSettings);
        int port = freePort();
        settingsService.save(new OpdsServerSettings(
                "127.0.0.1", port, false, "", "", false,
                managed.tls(), OpdsSecurityLimits.defaults()));

        assertThat(rawSettings.values.get("opds.tls.keyStorePassword"))
                .startsWith("mhlenc:v1:")
                .doesNotContain(managed.tls().keyStorePassword());

        OpdsServerSettings runtime = settingsService.load();
        assertThat(runtime.tls().keyStorePassword()).isEqualTo(managed.tls().keyStorePassword());
        assertThat(System.getProperty("myhomelib.opds.tls.keyStorePassword")).isNull();

        server = new JdkOpdsServer(new OpdsCatalogService(emptyCatalog()), null);
        OpdsServerStatus started = server.start(runtime);
        assertThat(started.running()).isTrue();
        assertThat(started.baseUrl()).startsWith("https://");

        HttpClient client = HttpClient.newBuilder().sslContext(trusting(managed.tls())).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/opds")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("MyHomeLib");
    }

    private static SSLContext trusting(OpdsTlsSettings tls) throws Exception {
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
            trust.setCertificateEntry("opds", certificate);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return ssl;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static OpdsCatalogQueryPort emptyCatalog() {
        return new OpdsCatalogQueryPort() {
            @Override public OpdsPage<OpdsFacetDto> authors(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
            @Override public OpdsPage<OpdsFacetDto> series(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
            @Override public OpdsPage<OpdsFacetDto> genres(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
            @Override public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) { return new OpdsPage<>(java.util.List.of(), 0, query.offset(), query.limit()); }
            @Override public Optional<OpdsBookDto> book(String bookId) { return Optional.empty(); }
        };
    }

    private static final class InMemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((k, v) -> { if (k.startsWith(prefix)) result.put(k, v); });
            return result;
        }
        @Override public void replaceByPrefix(String prefix, Map<String, String> replacement) {
            values.keySet().removeIf(k -> k.startsWith(prefix));
            replacement.forEach((k, v) -> { if (v != null) values.put(k, v); });
        }
    }
}
