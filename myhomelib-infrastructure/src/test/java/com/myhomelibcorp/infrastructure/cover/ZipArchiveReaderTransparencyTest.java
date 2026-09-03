package com.myhomelibcorp.infrastructure.cover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipArchiveReaderTransparencyTest {

    private final ZipArchiveReader reader = new ZipArchiveReader();

    @Test
    void distinguishesMissingEntryFromCorruptArchive(@TempDir Path dir) throws Exception {
        Path valid = dir.resolve("valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid))) {
            out.putNextEntry(new ZipEntry("book.fb2"));
            out.write("<FictionBook/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThat(reader.readEntry(valid, "missing.fb2")).isEmpty();

        Path corrupt = dir.resolve("corrupt.zip");
        Files.writeString(corrupt, "not a zip archive");
        assertThatThrownBy(() -> reader.listEntries(corrupt))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("corrupt.zip");
        assertThatThrownBy(() -> reader.readEntry(corrupt, "book.fb2"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("book.fb2");
    }
}
