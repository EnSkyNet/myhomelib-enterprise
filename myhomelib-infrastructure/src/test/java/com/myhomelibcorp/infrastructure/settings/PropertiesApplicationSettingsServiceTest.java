package com.myhomelibcorp.infrastructure.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
