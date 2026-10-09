package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavCredentialStore;
import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopIntegrationBackendWebDavSafetyTest {
    @TempDir Path tempDir;

    @Test
    void failedSecretDeletionPreservesEndpointAndCredentialsForRetry() throws Exception {
        withDataDir(() -> {
            try {
                String endpoint = "https://dav.example/books/";
                FakeSettings settings = new FakeSettings();
                settings.put("sync.webdav.endpoint", endpoint);
                FaultInjectingSecretStore secrets = new FaultInjectingSecretStore();
                WebDavCredentialStore credentialStore = new WebDavCredentialStore(secrets, URI.create(endpoint));
                credentialStore.createAndSave("alice", "secret");
                secrets.failOnceOnDelete(2);

                DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                        settings, mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                        new ObjectMapper(), List.<AiProvider>of());
                injectSecretStore(backend, secrets);

                var result = backend.clearWebDavConfiguration();

                assertThat(result.success()).isFalse();
                assertThat(result.userMessage().key()).isEqualTo("ui.integration.webdav.clear_failed");
                assertThat(settings.get("sync.webdav.endpoint", "")).isEqualTo(endpoint);
                assertThat(credentialStore.load()).isPresent();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void successfulClearRemovesEndpointAndCredentials() throws Exception {
        withDataDir(() -> {
            try {
                String endpoint = "https://dav.example/books/";
                FakeSettings settings = new FakeSettings();
                settings.put("sync.webdav.endpoint", endpoint);
                FaultInjectingSecretStore secrets = new FaultInjectingSecretStore();
                WebDavCredentialStore credentialStore = new WebDavCredentialStore(secrets, URI.create(endpoint));
                credentialStore.createAndSave("alice", "secret");

                DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                        settings, mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                        new ObjectMapper(), List.<AiProvider>of());
                injectSecretStore(backend, secrets);

                var result = backend.clearWebDavConfiguration();

                assertThat(result.success()).isTrue();
                assertThat(settings.get("sync.webdav.endpoint", "")).isBlank();
                assertThat(credentialStore.load()).isEmpty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void injectSecretStore(DesktopIntegrationBackend backend, SecretStore store) throws Exception {
        Field field = DesktopIntegrationBackend.class.getDeclaredField("resolvedSecretStore");
        field.setAccessible(true);
        field.set(backend, Optional.of(store));
    }

    private void withDataDir(Runnable action) {
        String old = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        try { action.run(); }
        finally {
            if (old == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", old);
        }
    }

    private static final class FaultInjectingSecretStore implements SecretStore {
        private final Map<String, String> values = new LinkedHashMap<>();
        private int failDeleteAt = -1;
        private int deleteCount;

        void failOnceOnDelete(int mutation) { failDeleteAt = mutation; deleteCount = 0; }
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) {
            deleteCount++;
            if (deleteCount == failDeleteAt) {
                failDeleteAt = -1;
                throw new IllegalStateException("injected delete failure");
            }
            values.remove(key);
        }
        @Override public String backendId() { return "test-secret-store"; }
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
