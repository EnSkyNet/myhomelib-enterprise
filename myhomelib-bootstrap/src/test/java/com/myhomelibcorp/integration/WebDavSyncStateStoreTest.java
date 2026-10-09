package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebDavSyncStateStoreTest {

    @Test
    void filtersInvalidCursorEntriesAndTreatsMalformedJsonAsEmptyState() {
        MapSettings settings = new MapSettings();
        settings.put("sync.webdav.cursors", "{\"ok\":3,\"negative\":-1,\"blank\":2}");
        WebDavSyncStateStore store = new WebDavSyncStateStore(settings, ObjectMapper::new);

        assertThat(store.loadCursors()).containsEntry("ok", 3L).containsEntry("blank", 2L)
                .doesNotContainKey("negative");

        settings.put("sync.webdav.cursors", "{ broken");
        assertThat(store.loadCursors()).isEmpty();
    }

    @Test
    void commitResetAndDeviceIdentityPreserveExistingContracts() {
        MapSettings settings = new MapSettings();
        WebDavSyncStateStore store = new WebDavSyncStateStore(settings, ObjectMapper::new);

        String first = store.ensureDeviceId();
        assertThat(store.ensureDeviceId()).isEqualTo(first).startsWith("device-");

        store.commit("hash-a", Map.of("device-a", 7L));
        assertThat(store.lastLocalHash()).isEqualTo("hash-a");
        assertThat(store.loadCursors()).containsExactly(Map.entry("device-a", 7L));

        store.reset();
        assertThat(store.lastLocalHash()).isEmpty();
        assertThat(store.loadCursors()).isEmpty();
        assertThat(store.ensureDeviceId()).isEqualTo(first);
    }

    private static final class MapSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
