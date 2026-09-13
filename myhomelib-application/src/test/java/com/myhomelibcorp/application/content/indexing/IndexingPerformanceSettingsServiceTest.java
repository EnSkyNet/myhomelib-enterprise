package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingPerformanceSettingsServiceTest {
    @Test
    void profileAndBatteryPreferencePersistThroughSettingsPort() {
        MemorySettings settings = new MemorySettings();
        IndexingPerformanceSettingsService first = new IndexingPerformanceSettingsService(settings);
        first.save(new IndexingPerformanceSettings(IndexingResourceProfile.ECO, false));

        IndexingPerformanceSettings loaded = new IndexingPerformanceSettingsService(settings).load();
        assertThat(loaded.profile()).isEqualTo(IndexingResourceProfile.ECO);
        assertThat(loaded.pauseOnBattery()).isFalse();
        assertThat(loaded.workerThreads(16)).isEqualTo(1);
        assertThat(loaded.ioBytesPerSecond()).isPositive();
    }

    @Test
    void invalidStoredProfileFallsBackToBalanced() {
        MemorySettings settings = new MemorySettings();
        settings.put(IndexingPerformanceSettingsService.PROFILE_KEY, "broken");
        assertThat(new IndexingPerformanceSettingsService(settings).load().profile())
                .isEqualTo(IndexingResourceProfile.BALANCED);
    }

    static final class MemorySettings implements ApplicationSettingsPort {
        final Map<String,String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String,String> result = new LinkedHashMap<>();
            values.forEach((k,v) -> { if (k.startsWith(prefix)) result.put(k,v); });
            return result;
        }
    }
}
