package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebDavCredentialStoreTest {
    @Test
    void storesCredentialsAndSharedKeyOnlyThroughSecretStore() {
        MemorySecretStore nativeStore = new MemorySecretStore();
        WebDavCredentialStore store = new WebDavCredentialStore(nativeStore, URI.create("https://dav.example/books/"));
        WebDavSyncSecrets secrets = store.createAndSave("alice", "very-secret-password");

        WebDavSyncSecrets restored = store.load().orElseThrow();
        assertThat(restored.username()).isEqualTo("alice");
        assertThat(restored.password()).isEqualTo("very-secret-password");
        assertThat(restored.syncKeyBase64()).isEqualTo(secrets.syncKeyBase64());
        assertThat(restored.rotationInProgress()).isFalse();
        assertThat(nativeStore.values).hasSize(3);
        assertThat(nativeStore.values.keySet()).allMatch(key -> key.startsWith("myhomelib.sync.webdav."));

        store.delete();
        assertThat(store.load()).isEmpty();
    }

    @Test
    void rotationRetainsExactlyOnePreviousKeyUntilMigrationCompletes() {
        MemorySecretStore nativeStore = new MemorySecretStore();
        WebDavCredentialStore store = new WebDavCredentialStore(nativeStore, URI.create("https://dav.example/books/"));
        WebDavSyncSecrets initial = store.createAndSave("alice", "pw");

        WebDavSyncSecrets rotating = store.rotateSyncKey();
        assertThat(rotating.rotationInProgress()).isTrue();
        assertThat(rotating.previousSyncKeyBase64()).isEqualTo(initial.syncKeyBase64());
        assertThat(rotating.syncKeyBase64()).isNotEqualTo(initial.syncKeyBase64());
        assertThat(nativeStore.values).hasSize(4);
        assertThatThrownBy(store::rotateSyncKey).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Complete");

        WebDavSyncSecrets completed = store.completeSyncKeyRotation();
        assertThat(completed.rotationInProgress()).isFalse();
        assertThat(completed.syncKeyBase64()).isEqualTo(rotating.syncKeyBase64());
        assertThat(nativeStore.values).hasSize(3);
    }

    private static final class MemorySecretStore implements SecretStore {
        private final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public String backendId() { return "memory-native-test"; }
    }
}
