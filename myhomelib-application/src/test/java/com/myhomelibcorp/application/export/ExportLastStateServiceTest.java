package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportLastStateServiceTest {
    @Test
    void preservesExplicitlyBlankAdHocFieldsInsteadOfFallingBackToProfileDefaults() {
        MemorySettings settings = new MemorySettings();
        ExportLastStateService service = new ExportLastStateService(settings);
        ExportRequest request = ExportRequest.builder()
                .destinationFolder(Path.of("."))
                .format(ExportRequest.ExportFormat.FB2)
                .collisionPolicy(ExportRequest.CollisionPolicy.RENAME)
                .customFileNameTemplate("")
                .subfolderTemplate("")
                .postActionProfileId("")
                .profileId("profile-1")
                .extractOnly(false)
                .build();

        service.save(request);
        ExportLastStateService.State state = service.load();

        assertThat(state.profileId()).isEqualTo("profile-1");
        assertThat(state.hasFilenameTemplate()).isTrue();
        assertThat(state.filenameTemplate()).isEmpty();
        assertThat(state.hasSubfolderTemplate()).isTrue();
        assertThat(state.subfolderTemplate()).isEmpty();
        assertThat(state.hasPostAction()).isTrue();
        assertThat(state.postActionId()).isEmpty();
        assertThat(state.extractOnly()).isFalse();
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> data = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return data.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) data.remove(key); else data.put(key, value); }
        @Override public void remove(String key) { data.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            data.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
