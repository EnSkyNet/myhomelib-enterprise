package com.myhomelibcorp.infrastructure.importer.rar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RarImporterSafetyTest {

    @Test
    void rejectsRarThroughSymlinkedParentBeforeJunrarOpensPath(@TempDir Path dir) throws Exception {
        Path realDir = Files.createDirectory(dir.resolve("real-rar"));
        Files.writeString(realDir.resolve("books.rar"), "not-even-a-rar");
        Path alias = dir.resolve("alias-rar");
        Files.createSymbolicLink(alias, realDir.getFileName());

        RarImporter importer = new RarImporter();

        assertThatThrownBy(() -> importer.importBooks(alias.resolve("books.rar")))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class);
    }
}
