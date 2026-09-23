package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Base64;
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


    @Test
    void failedSaveRestoresPreviousCompleteCredentialSet() {
        FaultInjectingSecretStore nativeStore = new FaultInjectingSecretStore();
        WebDavCredentialStore store = new WebDavCredentialStore(nativeStore, URI.create("https://dav.example/books/"));
        WebDavSyncSecrets original = store.createAndSave("alice", "old-password");
        Map<String, String> before = Map.copyOf(nativeStore.values);
        nativeStore.failOnceOnMutation(2);

        assertThatThrownBy(() -> store.save(new WebDavSyncSecrets(
                "bob", "new-password", Base64.getEncoder().encodeToString(new byte[32]), original.syncKeyBase64())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected");

        assertThat(nativeStore.values).isEqualTo(before);
        assertThat(store.load()).contains(original);
    }

    @Test
    void failedDeleteRestoresPreviousCompleteCredentialSet() {
        FaultInjectingSecretStore nativeStore = new FaultInjectingSecretStore();
        WebDavCredentialStore store = new WebDavCredentialStore(nativeStore, URI.create("https://dav.example/books/"));
        WebDavSyncSecrets original = store.createAndSave("alice", "pw");
        Map<String, String> before = Map.copyOf(nativeStore.values);
        nativeStore.failOnceOnMutation(2);

        assertThatThrownBy(store::delete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected");

        assertThat(nativeStore.values).isEqualTo(before);
        assertThat(store.load()).contains(original);
    }

    private static class MemorySecretStore implements SecretStore {
        protected final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public String backendId() { return "memory-native-test"; }
    }

    private static final class FaultInjectingSecretStore extends MemorySecretStore {
        private int failOnMutation = -1;
        private int mutationCount;

        void failOnceOnMutation(int mutation) {
            this.failOnMutation = mutation;
            this.mutationCount = 0;
        }

        @Override public void write(String key, String secret) {
            failIfRequested();
            super.write(key, secret);
        }

        @Override public void delete(String key) {
            failIfRequested();
            super.delete(key);
        }

        private void failIfRequested() {
            mutationCount++;
            if (mutationCount == failOnMutation) {
                failOnMutation = -1;
                throw new IllegalStateException("injected secret-store mutation failure");
            }
        }
    }
}
