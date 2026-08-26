package com.myhomelibcorp.application.action;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookActionProfileServiceTest {
    @Test
    void persistsOrderCommandsAndDeletesStaleCommandKeys() {
        FakeSettings settings = new FakeSettings();
        BookActionProfileService service = new BookActionProfileService(settings);
        BookActionProfile profile = new BookActionProfile("reader", "Reader", true, List.of(
                new BookActionCommand("reader.exe", "--file \"%FILE%\"", "%DIR%", true),
                new BookActionCommand("logger.exe", "\"%TITLE%\"", "", false)));

        service.save(profile);
        assertThat(service.loadProfiles()).containsExactly(profile);

        BookActionProfile shorter = new BookActionProfile("reader", "Reader", true,
                List.of(profile.commands().getFirst()));
        service.save(shorter);

        assertThat(service.loadProfiles()).containsExactly(shorter);
        assertThat(settings.values.keySet()).noneMatch(k -> k.contains(".command.1."));
    }

    @Test
    void migratesLegacyPostCommandOnceWithoutDeletingLegacySettings() {
        FakeSettings settings = new FakeSettings();
        settings.put("export.postCommand", "\"C:\\Program Files\\Tool\\tool.exe\" --file \"%FILE%\"");
        settings.putBoolean("export.runPostCommand", true);
        BookActionProfileService service = new BookActionProfileService(settings);

        List<BookActionProfile> profiles = service.loadProfiles();

        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().id()).isEqualTo("legacy-post-command");
        assertThat(profiles.getFirst().enabled()).isTrue();
        assertThat(profiles.getFirst().commands().getFirst().executable())
                .isEqualTo("C:\\Program Files\\Tool\\tool.exe");
        assertThat(settings.get("export.postCommand", "")).isNotBlank();
        assertThat(settings.getBoolean("bookActions.migration.legacyPostCommand.v1", false)).isTrue();
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> out = new LinkedHashMap<>();
            values.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k, v); });
            return out;
        }
    }
}
