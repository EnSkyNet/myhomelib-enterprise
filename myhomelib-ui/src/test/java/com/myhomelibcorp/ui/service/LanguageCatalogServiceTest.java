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
    void createsDefaultsAndDiscoversExternalRussianCatalogue() throws Exception {
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

        Files.writeString(data.resolve("Lang/ru.json"), """
                {
                  "schemaVersion": 3,
                  "code": "ru",
                  "name": "Русский",
                  "translations": {
                    "Колекція": "Коллекция",
                    "Завантажити": "Скачать"
                  },
                  "genres": {
                    "sf": "Научная фантастика"
                  },
                  "genreAliases": {
                    "0.1.12": "sf"
                  },
                  "genreGroups": {
                    "speculative": "Фантастика"
                  },
                  "genreParents": {
                    "sf": "speculative"
                  },
                  "legacyBaseAliases": {
                    "0.1": "speculative"
                  }
                }
                """, StandardCharsets.UTF_8);

        service.refresh();

        assertThat(service.availableLanguages()).containsEntry("ru", "Русский");
        assertThat(service.translations("ru")).hasValueSatisfying(map ->
                assertThat(map).containsEntry("Колекція", "Коллекция")
                        .containsEntry("Завантажити", "Скачать"));
        assertThat(service.genreName("ru", "sf", "fallback")).isEqualTo("Научная фантастика");
        assertThat(service.genreName("ru", "0.1.12", "fallback")).isEqualTo("Научная фантастика");
        assertThat(service.genreName("ru", "missing-code", "Власний жанр")).isEqualTo("Власний жанр");
        assertThat(service.genreName("ru", "missing-code", "missing-code")).isEmpty();
        assertThat(service.genreName("ru", "0.1", "0.1")).isEqualTo("Фантастика");
        assertThat(service.genreName("ru", "0.1.999", "0.1.999")).isEqualTo("Фантастика");
        assertThat(service.shouldDisplayGenre("ru", "0.1", java.util.List.of("0.1", "sf"))).isFalse();
        assertThat(service.shouldDisplayGenre("ru", "sf", java.util.List.of("0.1", "sf"))).isTrue();
        assertThat(Files.readString(data.resolve("config/available-languages.txt"), StandardCharsets.UTF_8))
                .contains("ru=Русский");
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
