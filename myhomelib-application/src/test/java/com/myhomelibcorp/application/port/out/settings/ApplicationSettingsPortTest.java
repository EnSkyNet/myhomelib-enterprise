package com.myhomelibcorp.application.port.out.settings;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSettingsPortTest {
    @Test
    void malformedBooleanFallsBackToRequestedDefault() {
        MapSettings settings = new MapSettings();
        settings.put("confirm", "not-a-boolean");

        assertThat(settings.getBoolean("confirm", true)).isTrue();
        assertThat(settings.getBoolean("confirm", false)).isFalse();
    }

    @Test
    void booleanParsingIsTrimmedAndCaseInsensitive() {
        MapSettings settings = new MapSettings();
        settings.put("enabled", "  TrUe  ");
        settings.put("disabled", " FALSE ");

        assertThat(settings.getBoolean("enabled", false)).isTrue();
        assertThat(settings.getBoolean("disabled", true)).isFalse();
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
