package com.myhomelibcorp.application.port.out.settings;

import java.util.Map;
import java.util.Objects;

public interface ApplicationSettingsPort {
    String get(String key, String defaultValue);
    void put(String key, String value);
    void remove(String key);
    Map<String, String> findByPrefix(String prefix);

    /**
     * Replaces one namespaced settings slice as a logical unit. Implementations backed by a
     * single file should override this method so the replacement is persisted with one atomic
     * write rather than one write per key.
     */
    default void replaceByPrefix(String prefix, Map<String, String> values) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(values, "values");
        for (String key : values.keySet()) {
            if (key == null || !key.startsWith(prefix)) {
                throw new IllegalArgumentException("Setting key is outside prefix " + prefix + ": " + key);
            }
        }
        for (String key : findByPrefix(prefix).keySet()) remove(key);
        values.forEach((key, value) -> {
            if (value != null) put(key, value);
        });
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, Boolean.toString(defaultValue)));
    }

    default int getInt(String key, int defaultValue) {
        try { return Integer.parseInt(get(key, Integer.toString(defaultValue))); }
        catch (Exception ignored) { return defaultValue; }
    }

    default void putBoolean(String key, boolean value) { put(key, Boolean.toString(value)); }
    default void putInt(String key, int value) { put(key, Integer.toString(value)); }
}
