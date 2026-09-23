package com.myhomelibcorp.shared.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeZipSupportTest {
    @TempDir Path temp;

    @Test
    void opensUtf8ArchiveThroughNoFollowChannel() throws Exception {
        Path zip = writeZip(temp.resolve("books.zip"), StandardCharsets.UTF_8, "книга.fb2");
        try (var opened = SafeZipSupport.openDetected(zip, "test ZIP")) {
            assertThat(opened.getEntry("книга.fb2")).isNotNull();
        }
    }

    @Test
    void detectsWindows1251NamesWithoutReopeningThePath() throws Exception {
        Path zip = writeZip(temp.resolve("legacy.zip"), Charset.forName("Windows-1251"), "книга.fb2");
        try (var opened = SafeZipSupport.openDetected(zip, "legacy ZIP")) {
            assertThat(opened.getEntry("книга.fb2")).isNotNull();
        }
    }

    @Test
    void rejectsArchiveReachedThroughSymlinkedParent() throws Exception {
        Path real = Files.createDirectories(temp.resolve("real"));
        writeZip(real.resolve("books.zip"), StandardCharsets.UTF_8, "book.fb2");
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        assertThatThrownBy(() -> SafeZipSupport.openDetected(alias.resolve("books.zip"), "test ZIP"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
    }

    private Path writeZip(Path path, Charset charset, String entryName) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path), charset)) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write("book".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
