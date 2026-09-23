package com.myhomelibcorp.infrastructure.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesApplicationSettingsServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void replaceByPrefixIsExactAndPreservesUnrelatedSettingsAcrossReload() {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("settings-data").toString());

        PropertiesApplicationSettingsService settings = new PropertiesApplicationSettingsService();
        settings.put("filter.global.language", "ru");
        settings.put("filter.global.author", "stale");
        settings.put("unrelated.setting", "keep");

        settings.replaceByPrefix("filter.global.", Map.of("filter.global.language", "uk"));

        assertThat(settings.findByPrefix("filter.global."))
                .containsExactly(Map.entry("filter.global.language", "uk"));
        assertThat(settings.get("unrelated.setting", "")).isEqualTo("keep");

        PropertiesApplicationSettingsService reloaded = new PropertiesApplicationSettingsService();
        assertThat(reloaded.findByPrefix("filter.global."))
                .containsExactly(Map.entry("filter.global.language", "uk"));
        assertThat(reloaded.get("unrelated.setting", "")).isEqualTo("keep");
    }

    @Test
    void corruptSettingsRemainUntouchedAndCannotBeOverwrittenWithDefaults() throws Exception {
        Path data = tempDir.resolve("settings-corrupt");
        System.setProperty("myhomelib.dataDir", data.toString());
        Path file = data.resolve("config/myhomelib.properties");
        Files.createDirectories(file.getParent());
        String corrupt = "valid.before.failure=must-not-leak\ninvalid.unicode=" + "\\" + "u12GG\n";
        Files.writeString(file, corrupt, StandardCharsets.ISO_8859_1);

        PropertiesApplicationSettingsService settings = new PropertiesApplicationSettingsService();

        assertThat(settings.get("valid.before.failure", "default")).isEqualTo("default");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.put("new.setting", "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing settings file could not be loaded");
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(corrupt);
        assertThat(settings.get("new.setting", "missing")).isEqualTo("missing");
    }

    @Test
    void failedSymlinkedSaveDoesNotChangeInMemorySettingsOrExternalFile() throws Exception {
        Path data = tempDir.resolve("settings-data");
        System.setProperty("myhomelib.dataDir", data.toString());
        PropertiesApplicationSettingsService settings = new PropertiesApplicationSettingsService();
        settings.put("test.value", "old");

        Path file = data.resolve("config/myhomelib.properties");
        Files.delete(file);
        Path outside = tempDir.resolve("outside.properties");
        Files.writeString(outside, "FOREIGN", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(canCreateSymlink(file, outside));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.put("test.value", "new"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(settings.get("test.value", "")).isEqualTo("old");
        assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(file)).isTrue();
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
