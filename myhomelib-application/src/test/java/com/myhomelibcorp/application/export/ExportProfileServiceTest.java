package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportProfileServiceTest {
    @Test
    void migratesLegacyDefaultsAndPersistsProfileSpecificSettings() {
        FakeSettings settings = new FakeSettings();
        settings.put("export.filenameTemplate", "%a - %t");
        settings.put("export.subfolderTemplate", "");
        settings.putBoolean("export.runPostCommand", true);
        ExportProfileService service = new ExportProfileService(settings);

        ExportProfile migrated = service.loadProfiles().getFirst();
        assertThat(migrated.id()).isEqualTo("default-export");
        assertThat(migrated.filenameTemplate()).isEqualTo("%n2 - %t");
        assertThat(migrated.subfolderTemplate()).isEqualTo("%a/%s");
        assertThat(migrated.postActionProfileId()).isEqualTo("legacy-post-command");

        ExportProfile custom = new ExportProfile("device", "Reader device", ExportRequest.ExportFormat.EPUB,
                "/reader", ExportRequest.CollisionPolicy.ASK, false, "%t", "%a/%y", "send-device");
        service.save(custom);
        assertThat(service.findById("device")).contains(custom);
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
