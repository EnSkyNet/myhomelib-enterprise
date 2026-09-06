package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsSettingsServiceTest {

    @BeforeAll
    static void configureEncryptionKey() {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void saveHashesPasswordAndReplacesNamespaceOnce() {
        InMemorySettings settings = new InMemorySettings();
        OpdsSettingsService service = new OpdsSettingsService(settings);

        service.save(new OpdsServerSettings("127.0.0.1", 8088, true, "reader", "secret", true));

        String stored = settings.values.get("opds.password");
        assertThat(stored).startsWith("pbkdf2-sha256$").doesNotContain("secret");
        assertThat(OpdsPasswordHash.matches("secret", stored)).isTrue();
        assertThat(OpdsPasswordHash.matches("wrong", stored)).isFalse();
        assertThat(settings.replaceCalls).isEqualTo(1);
        assertThat(settings.putCalls).isZero();
    }

    @Test
    void loadMigratesLegacyPlaintextPasswordAtomically() {
        InMemorySettings settings = new InMemorySettings();
        settings.values.put("opds.bindAddress", "127.0.0.1");
        settings.values.put("opds.port", "8088");
        settings.values.put("opds.basicAuthEnabled", "true");
        settings.values.put("opds.username", "reader");
        settings.values.put("opds.password", "legacy-secret");
        settings.values.put("opds.autostart", "true");
        OpdsSettingsService service = new OpdsSettingsService(settings);

        OpdsServerSettings loaded = service.load();

        assertThat(loaded.password()).startsWith("pbkdf2-sha256$");
        assertThat(settings.values.get("opds.password")).isEqualTo(loaded.password());
        assertThat(settings.values.values()).doesNotContain("legacy-secret");
        assertThat(OpdsPasswordHash.matches("legacy-secret", loaded.password())).isTrue();
        assertThat(settings.replaceCalls).isEqualTo(1);
    }

    @Test
    void blankPasswordPreservesExistingHashWhenAuthRemainsEnabled() {
        InMemorySettings settings = new InMemorySettings();
        OpdsSettingsService service = new OpdsSettingsService(settings);
        service.save(new OpdsServerSettings("127.0.0.1", 8088, true, "reader", "secret", false));
        String before = settings.values.get("opds.password");

        service.save(new OpdsServerSettings("127.0.0.1", 9090, true, "reader", "", true));

        assertThat(settings.values.get("opds.password")).isEqualTo(before);
        assertThat(service.load().port()).isEqualTo(9090);
        assertThat(service.hasStoredPassword()).isTrue();
    }

    @Test
    void disablingAuthClearsStoredPassword() {
        InMemorySettings settings = new InMemorySettings();
        OpdsSettingsService service = new OpdsSettingsService(settings);
        service.save(new OpdsServerSettings("127.0.0.1", 8088, true, "reader", "secret", false));

        service.save(new OpdsServerSettings("127.0.0.1", 8088, false, "reader", "", false));

        assertThat(settings.values.get("opds.password")).isEmpty();
        assertThat(service.hasStoredPassword()).isFalse();
    }


    @Test
    void persistsTlsMetadataLimitsAndEncryptedTlsKeyStorePassword() {
        InMemorySettings settings = new InMemorySettings();
        OpdsSettingsService service = new OpdsSettingsService(settings);
        OpdsTlsSettings tls = new OpdsTlsSettings(true, "/secure/opds.p12", "PKCS12", "super-secret");
        OpdsSecurityLimits limits = new OpdsSecurityLimits(12, 24, 4, 30, 90, false);

        service.save(new OpdsServerSettings("0.0.0.0", 8443, true, "reader", "catalog-secret", true, tls, limits));

        assertThat(settings.values.get("opds.tls.enabled")).isEqualTo("true");
        assertThat(settings.values.get("opds.tls.keyStorePath")).isEqualTo("/secure/opds.p12");
        assertThat(settings.values.get("opds.tls.keyStoreType")).isEqualTo("PKCS12");
        assertThat(settings.values.get("opds.tls.keyStorePassword"))
                .startsWith("mhlenc:v1:")
                .doesNotContain("super-secret");
        assertThat(settings.values.values()).doesNotContain("super-secret", "catalog-secret");

        OpdsServerSettings loaded = service.load();
        assertThat(loaded.tls().enabled()).isTrue();
        assertThat(loaded.tls().keyStorePath()).isEqualTo("/secure/opds.p12");
        assertThat(loaded.tls().keyStorePassword()).isEqualTo("super-secret");
        assertThat(loaded.limits()).isEqualTo(limits);
        assertThat(OpdsPasswordHash.matches("catalog-secret", loaded.password())).isTrue();
    }

    @Test
    void loadMigratesLegacyPlaintextTlsPasswordIntoEncryptionEnvelope() {
        InMemorySettings settings = new InMemorySettings();
        settings.values.put("opds.tls.enabled", "true");
        settings.values.put("opds.tls.keyStorePath", "/secure/legacy.p12");
        settings.values.put("opds.tls.keyStoreType", "PKCS12");
        settings.values.put("opds.tls.keyStorePassword", "legacy-tls-secret");
        OpdsSettingsService service = new OpdsSettingsService(settings);

        OpdsServerSettings loaded = service.load();

        assertThat(loaded.tls().keyStorePassword()).isEqualTo("legacy-tls-secret");
        assertThat(settings.values.get("opds.tls.keyStorePassword"))
                .startsWith("mhlenc:v1:")
                .doesNotContain("legacy-tls-secret");
    }

    @Test
    void loadMigratesAuthenticatedLegacyTlsCiphertextToCurrentEnvelopeIdempotently() throws Exception {
        InMemorySettings settings = new InMemorySettings();
        String legacy = legacyCiphertext("legacy-encrypted-tls-secret");
        settings.values.put("opds.tls.enabled", "true");
        settings.values.put("opds.tls.keyStorePath", "/secure/legacy-encrypted.p12");
        settings.values.put("opds.tls.keyStoreType", "PKCS12");
        settings.values.put("opds.tls.keyStorePassword", legacy);
        OpdsSettingsService service = new OpdsSettingsService(settings);

        OpdsServerSettings loaded = service.load();
        String migrated = settings.values.get("opds.tls.keyStorePassword");

        assertThat(loaded.tls().keyStorePassword()).isEqualTo("legacy-encrypted-tls-secret");
        assertThat(migrated).startsWith("mhlenc:v1:").isNotEqualTo(legacy);
        int replaceCallsAfterMigration = settings.replaceCalls;

        OpdsServerSettings loadedAgain = service.load();
        assertThat(settings.values.get("opds.tls.keyStorePassword")).isEqualTo(migrated);
        assertThat(loadedAgain.tls().keyStorePassword()).isEqualTo("legacy-encrypted-tls-secret");
        assertThat(settings.replaceCalls).isEqualTo(replaceCallsAfterMigration);
    }

    @Test
    void securityLimitsAreClampedToSafeBounds() {
        OpdsSecurityLimits limits = new OpdsSecurityLimits(0, 99999, 0, 0, 999999, true);

        assertThat(limits.maxConcurrentRequests()).isEqualTo(1);
        assertThat(limits.listenBacklog()).isEqualTo(1024);
        assertThat(limits.authFailuresPerWindow()).isEqualTo(1);
        assertThat(limits.authWindowSeconds()).isEqualTo(1);
        assertThat(limits.authBlockSeconds()).isEqualTo(86400);
    }


    private static String legacyCiphertext(String plaintext) throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (0x40 + i);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(1 + nonce.length + ciphertext.length);
        buffer.put((byte) 1).put(nonce).put(ciphertext);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static final class InMemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        private int replaceCalls;
        private int putCalls;

        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { putCalls++; if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((k, v) -> { if (k.startsWith(prefix)) result.put(k, v); });
            return result;
        }
        @Override public void replaceByPrefix(String prefix, Map<String, String> replacement) {
            replaceCalls++;
            values.keySet().removeIf(k -> k.startsWith(prefix));
            replacement.forEach((k, v) -> { if (v != null) values.put(k, v); });
        }
    }
}
