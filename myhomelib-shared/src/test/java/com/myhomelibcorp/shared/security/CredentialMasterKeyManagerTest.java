package com.myhomelibcorp.shared.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialMasterKeyManagerTest {
    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString(filledKey((byte) 7));

    @TempDir Path tempDir;

    private static byte[] filledKey(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @Test
    void migratesLegacyLocalKeyToNativeStoreAndDeletesRawFileOnlyAfterVerification() throws Exception {
        Path legacy = tempDir.resolve(CredentialMasterKeyManager.LOCAL_KEY_FILE);
        Files.writeString(legacy, KEY_A + "\n", StandardCharsets.US_ASCII);
        InMemorySecretStore store = new InMemorySecretStore("native-test");
        SecretStoreProvider provider = provider(store);
        SecretStoreContext context = new SecretStoreContext(tempDir, false, "Windows 11", "tester");

        String resolved = CredentialMasterKeyManager.loadOrCreate(context, List.of(provider), () -> KEY_B, true);

        assertThat(resolved).isEqualTo(KEY_A);
        assertThat(store.read(CredentialMasterKeyManager.SECRET_NAME)).contains(KEY_A);
        assertThat(legacy).doesNotExist();
    }

    @Test
    void failedNativeVerificationDoesNotDeleteLegacyRawKey() throws Exception {
        Path legacy = tempDir.resolve(CredentialMasterKeyManager.LOCAL_KEY_FILE);
        Files.writeString(legacy, KEY_A + "\n", StandardCharsets.US_ASCII);
        InMemorySecretStore store = new InMemorySecretStore("broken-native") {
            @Override public Optional<String> read(String key) {
                Optional<String> value = super.read(key);
                return value.isPresent() ? Optional.of(KEY_B) : value;
            }
        };
        SecretStoreContext context = new SecretStoreContext(tempDir, false, "Windows 11", "tester");

        assertThatThrownBy(() -> CredentialMasterKeyManager.loadOrCreate(context, List.of(provider(store)), () -> KEY_B, true))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining("Native credential store is unavailable");
        assertThat(legacy).exists();
        assertThat(Files.readString(legacy)).contains(KEY_A);
    }

    @Test
    void windowsInstalledModeFailsClosedWhenNoNativeStoreExists() {
        SecretStoreContext context = new SecretStoreContext(tempDir, false, "Windows 11", "tester");

        assertThatThrownBy(() -> CredentialMasterKeyManager.loadOrCreate(context, List.of(), () -> KEY_A, true))
                .isInstanceOf(SecretStoreException.class)
                .hasMessageContaining("No supported native credential store");
        assertThat(tempDir.resolve(CredentialMasterKeyManager.LOCAL_KEY_FILE)).doesNotExist();
    }

    @Test
    void portableModeDeliberatelyUsesRestrictedLocalKeyInsteadOfMachineStore() {
        InMemorySecretStore store = new InMemorySecretStore("must-not-be-used");
        SecretStoreContext context = new SecretStoreContext(tempDir, true, "Windows 11", "tester");

        String resolved = CredentialMasterKeyManager.loadOrCreate(context, List.of(provider(store)), () -> KEY_A, true);

        assertThat(resolved).isEqualTo(KEY_A);
        assertThat(store.values).isEmpty();
        assertThat(tempDir.resolve(CredentialMasterKeyManager.LOCAL_KEY_FILE)).exists();
    }

    @Test
    void nonRequiredNativeFailureFallsBackWithoutLosingLegacyKey() throws Exception {
        Path legacy = tempDir.resolve(CredentialMasterKeyManager.LOCAL_KEY_FILE);
        Files.writeString(legacy, KEY_A, StandardCharsets.US_ASCII);
        SecretStore broken = new SecretStore() {
            @Override public Optional<String> read(String key) { throw new SecretStoreException("offline"); }
            @Override public void write(String key, String secret) { throw new SecretStoreException("offline"); }
            @Override public void delete(String key) {}
            @Override public String backendId() { return "linux-secret-service"; }
        };
        SecretStoreContext context = new SecretStoreContext(tempDir, false, "Linux", "tester");

        String resolved = CredentialMasterKeyManager.loadOrCreate(context, List.of(provider(broken)), () -> KEY_B, false);

        assertThat(resolved).isEqualTo(KEY_A);
        assertThat(legacy).exists();
    }

    private static SecretStoreProvider provider(SecretStore store) {
        return new SecretStoreProvider() {
            @Override public int priority() { return 100; }
            @Override public Optional<SecretStore> open(SecretStoreContext context) { return Optional.of(store); }
        };
    }

    private static class InMemorySecretStore implements SecretStore {
        private final String id;
        final Map<String, String> values = new HashMap<>();
        InMemorySecretStore(String id) { this.id = id; }
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public String backendId() { return id; }
    }
}
