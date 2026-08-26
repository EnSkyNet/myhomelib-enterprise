package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportHistoryServiceTest {
    @Test
    void storesNewestFirstAndCanClear() {
        FakeSettings settings = new FakeSettings();
        ExportHistoryService service = new ExportHistoryService(settings);
        ExportRequest first = ExportRequest.builder().destinationFolder(Path.of("one"))
                .format(ExportRequest.ExportFormat.FB2).profileName("First").build();
        ExportRequest second = ExportRequest.builder().destinationFolder(Path.of("two"))
                .format(ExportRequest.ExportFormat.EPUB).profileName("Second").build();

        service.record(first, 3, 2, 1, 0, false, 1000);
        service.record(second, 4, 3, 0, 1, false, 2000);

        assertThat(service.loadRecent(10)).extracting(ExportHistoryEntry::profileName)
                .containsExactly("Second", "First");
        service.clear();
        assertThat(service.loadRecent(10)).isEmpty();
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
        private final Map<String,String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String,String> findByPrefix(String prefix) {
            Map<String,String> result = new LinkedHashMap<>();
            values.forEach((k,v) -> { if (k.startsWith(prefix)) result.put(k,v); });
            return result;
        }
    }
}
