package com.myhomelibcorp.application.port.out.settings;

import java.util.Map;

public interface ApplicationSettingsPort {
    String get(String key, String defaultValue);
    void put(String key, String value);
    void remove(String key);
    Map<String, String> findByPrefix(String prefix);

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
