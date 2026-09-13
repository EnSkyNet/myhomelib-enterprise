package com.myhomelibcorp.infrastructure.importer.generic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GenericAudiobookImporterTest {
    @Test void acceptsMp3AndM4bAsLocalBooks() throws Exception {
        Path dir = Files.createTempDirectory("mhl-audio-import");
        Path mp3 = Files.write(dir.resolve("First.mp3"), new byte[]{1,2,3});
        Path m4b = Files.write(dir.resolve("Second.m4b"), new byte[]{4,5});
        try {
            GenericDocumentImporter importer = new GenericDocumentImporter();
            assertThat(importer.supports(mp3)).isTrue();
            assertThat(importer.supports(m4b)).isTrue();
            var first = importer.importBooks(mp3).findFirst().orElseThrow();
            var second = importer.importBooks(m4b).findFirst().orElseThrow();
            assertThat(first.getFileName()).isEqualTo("First.mp3");
            assertThat(second.getFileName()).isEqualTo("Second.m4b");
        } finally {
            Files.deleteIfExists(mp3); Files.deleteIfExists(m4b); Files.deleteIfExists(dir);
        }
    }
}
