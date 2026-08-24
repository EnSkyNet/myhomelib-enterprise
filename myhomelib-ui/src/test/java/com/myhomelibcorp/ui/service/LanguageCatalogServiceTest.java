package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCatalogServiceTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("myhomelib.launchDir");
        System.clearProperty("myhomelib.dataDir");
        System.clearProperty("myhomelib.langDir");
    }

    @Test
    void createsDefaultsAndSynchronizesNewLanguageFiles() throws Exception {
        Path launch = tempDir.resolve("app");
        Path data = tempDir.resolve("data");
        Files.createDirectories(launch);
        Files.createDirectories(data);
        System.setProperty("myhomelib.launchDir", launch.toString());
        System.setProperty("myhomelib.dataDir", data.toString());

        LanguageCatalogService service = new LanguageCatalogService();

        assertThat(service.availableLanguages()).containsKeys("uk", "en", "bg");
        assertThat(Files.isRegularFile(data.resolve("Lang/uk.json"))).isTrue();
        assertThat(Files.isRegularFile(data.resolve("config/available-languages.txt"))).isTrue();

        Files.writeString(data.resolve("Lang/pl.json"), """
                {
                  "code": "pl",
                  "name": "Polski",
                  "translations": {
                    "Колекція": "Kolekcja"
                  }
                }
                """, StandardCharsets.UTF_8);

        service.refresh();

        assertThat(service.availableLanguages()).containsEntry("pl", "Polski");
        assertThat(service.translations("pl")).hasValueSatisfying(map ->
                assertThat(map).containsEntry("Колекція", "Kolekcja"));
        assertThat(Files.readString(data.resolve("config/available-languages.txt"), StandardCharsets.UTF_8))
                .contains("pl=Polski");
    }

    @Test
    void languageListReflectsFilesCurrentlyPresentAfterFirstRun() throws Exception {
        Path launch = tempDir.resolve("app");
        Path data = tempDir.resolve("data");
        Files.createDirectories(launch);
        Files.createDirectories(data);
        System.setProperty("myhomelib.launchDir", launch.toString());
        System.setProperty("myhomelib.dataDir", data.toString());

        LanguageCatalogService service = new LanguageCatalogService();
        Files.delete(data.resolve("Lang/bg.json"));

        service.refresh();

        assertThat(service.availableLanguages()).doesNotContainKey("bg");
        assertThat(Files.readString(data.resolve("config/available-languages.txt"), StandardCharsets.UTF_8))
                .doesNotContain("bg=Български");
    }
}
