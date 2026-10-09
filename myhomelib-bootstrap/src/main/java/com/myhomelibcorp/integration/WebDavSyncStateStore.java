package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Persistent local state for WebDAV snapshot synchronization. */
final class WebDavSyncStateStore {
    private static final String PREFIX = "sync.webdav.";
    private static final String LAST_LOCAL_HASH = PREFIX + "lastLocalHash";
    private static final String CURSORS = PREFIX + "cursors";
    private static final String DEVICE_ID = "sync.deviceId";

    private final ApplicationSettingsPort settings;
    private final Supplier<ObjectMapper> mapperSupplier;

    WebDavSyncStateStore(ApplicationSettingsPort settings, Supplier<ObjectMapper> mapperSupplier) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mapperSupplier = Objects.requireNonNull(mapperSupplier, "mapperSupplier");
    }

    String lastLocalHash() {
        return settings.get(LAST_LOCAL_HASH, "").trim();
    }

    Map<String, Long> loadCursors() {
        String raw = settings.get(CURSORS, "").trim();
        if (raw.isBlank()) return new HashMap<>();
        try {
            Map<String, Long> parsed = mapper().readValue(raw, new TypeReference<Map<String, Long>>() { });
            Map<String, Long> clean = new HashMap<>();
            parsed.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && value >= 0) clean.put(key, value);
            });
            return clean;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    void saveCursors(Map<String, Long> cursors) {
        try {
            settings.put(CURSORS, mapper().writeValueAsString(cursors == null ? Map.of() : cursors));
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося зберегти курсор WebDAV-синхронізації", e);
        }
    }

    void commit(String localHash, Map<String, Long> cursors) {
        settings.put(LAST_LOCAL_HASH, localHash);
        saveCursors(cursors);
    }

    void reset() {
        settings.remove(LAST_LOCAL_HASH);
        settings.remove(CURSORS);
    }

    String ensureDeviceId() {
        String current = settings.get(DEVICE_ID, "").trim();
        if (!current.isBlank()) return current;
        String generated = "device-" + UUID.randomUUID();
        settings.put(DEVICE_ID, generated);
        return generated;
    }

    private ObjectMapper mapper() {
        return Objects.requireNonNull(mapperSupplier.get(), "mapperSupplier returned null");
    }
}
