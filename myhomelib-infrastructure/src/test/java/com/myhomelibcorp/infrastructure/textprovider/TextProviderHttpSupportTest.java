package com.myhomelibcorp.infrastructure.textprovider;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextProviderHttpSupportTest {
    private static final String PROPERTY = "myhomelib.test.textprovider.secret";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void rejectsNonHttpsRemoteEndpoint() {
        assertThatThrownBy(() -> TextProviderHttpSupport.requireHttpsUri(
                "http://example.test/translate", "endpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void runtimeCredentialHasPriorityWithoutPersistingSecret() {
        MapSettings settings = new MapSettings();
        settings.put("provider.secret", "plaintext-must-not-be-used");
        System.setProperty(PROPERTY, "runtime-secret");

        String credential = TextProviderHttpSupport.credential(
                settings, "provider.secret", PROPERTY, "MYHOMELIB_UNUSED_TEST_SECRET");

        assertThat(credential).isEqualTo("runtime-secret");
    }

    @Test
    void plaintextPersistedCredentialIsRejected() {
        MapSettings settings = new MapSettings();
        settings.put("provider.secret", "plaintext-secret");

        assertThatThrownBy(() -> TextProviderHttpSupport.credential(
                settings, "provider.secret", PROPERTY, "MYHOMELIB_UNUSED_TEST_SECRET"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be encrypted");
    }

    private static final class MapSettings implements ApplicationSettingsPort {
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
