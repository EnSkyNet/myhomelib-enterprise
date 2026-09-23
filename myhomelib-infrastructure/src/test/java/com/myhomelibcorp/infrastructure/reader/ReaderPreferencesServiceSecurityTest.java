package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderPreferencesServiceSecurityTest {
    @TempDir Path tempDir;

    @Test
    void symlinkedPreferencesFileIsNeitherReadOverwrittenNorDeleted() throws Exception {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        try {
            Path config = tempDir.resolve("config");
            Files.createDirectories(config);
            Path outside = tempDir.resolve("outside.json");
            Files.writeString(outside, "FOREIGN", StandardCharsets.UTF_8);
            Path preferences = config.resolve("reader-preferences.json");
            Assumptions.assumeTrue(canCreateSymlink(preferences, outside));

            ObjectMapper mapper = new ObjectMapper();
            ReaderPreferencesService service = new ReaderPreferencesService(mapper, new ReaderPreferencesJsonCodec(mapper));

            assertThat(service.loadPreferences()).isEqualTo(ReaderPreferences.builder().build());
            service.savePreferences(ReaderPreferences.builder().theme("dark").build());
            service.resetPreferences();

            assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("FOREIGN");
            assertThat(Files.isSymbolicLink(preferences)).isTrue();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    @Test
    void corruptPreferencesAreNotOverwrittenBySave() throws Exception {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", tempDir.resolve("corrupt-data").toString());
        try {
            Path preferences = tempDir.resolve("corrupt-data/config/reader-preferences.json");
            Files.createDirectories(preferences.getParent());
            String corrupt = "{\"theme\":\"dark\",broken";
            Files.writeString(preferences, corrupt, StandardCharsets.UTF_8);

            ObjectMapper mapper = new ObjectMapper();
            ReaderPreferencesService service = new ReaderPreferencesService(mapper, new ReaderPreferencesJsonCodec(mapper));

            assertThat(service.loadPreferences()).isEqualTo(ReaderPreferences.builder().build());
            service.savePreferences(ReaderPreferences.builder().theme("light").build());

            assertThat(Files.readString(preferences, StandardCharsets.UTF_8)).isEqualTo(corrupt);
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
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
