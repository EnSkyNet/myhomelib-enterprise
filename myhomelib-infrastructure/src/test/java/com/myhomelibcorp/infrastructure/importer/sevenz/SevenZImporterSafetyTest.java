package com.myhomelibcorp.infrastructure.importer.sevenz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SevenZImporterSafetyTest {

    @Test
    void rejectsSevenZThroughSymlinkedParentBeforeArchiveOpen(@TempDir Path dir) throws Exception {
        Path realDir = Files.createDirectory(dir.resolve("real-seven"));
        Files.writeString(realDir.resolve("books.7z"), "not-even-a-seven-z");
        Path alias = dir.resolve("alias-seven");
        Files.createSymbolicLink(alias, realDir.getFileName());

        SevenZImporter importer = new SevenZImporter();

        assertThatThrownBy(() -> importer.importBooks(alias.resolve("books.7z")))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class);
    }
}
