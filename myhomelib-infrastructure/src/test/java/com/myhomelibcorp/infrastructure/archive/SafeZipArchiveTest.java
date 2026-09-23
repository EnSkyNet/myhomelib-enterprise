package com.myhomelibcorp.infrastructure.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeZipArchiveTest {
    @TempDir
    Path temp;

    @Test
    void opensRegularZipThroughNoFollowChannel() throws Exception {
        Path zip = temp.resolve("books.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("book.fb2"));
            out.write("book".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (SafeZipArchive opened = SafeZipArchive.open(zip, StandardCharsets.UTF_8, "test zip")) {
            assertThat(opened.zip().getEntry("book.fb2")).isNotNull();
        }
    }

    @Test
    void keepsUtf8CyrillicNamesInsteadOfChoosingMojibakeLegacyDecoding() throws Exception {
        Path zip = temp.resolve("utf8-cyrillic.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("книга.fb2"));
            out.write("book".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (SafeZipArchive opened = SafeZipArchive.openDetected(zip, "utf8 zip")) {
            assertThat(opened.zip().getEntry("книга.fb2")).isNotNull();
            assertThat(opened.zip().getEntry("РєРЅРёРіР°.fb2")).isNull();
        }
    }

    @Test
    void detectsLegacyCyrillicEntryNamesOnTheAlreadyOpenedFile() throws Exception {
        Path zip = temp.resolve("legacy.zip");
        var legacy = java.nio.charset.Charset.forName("Windows-1251");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), legacy)) {
            out.putNextEntry(new ZipEntry("книга.fb2"));
            out.write("book".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (SafeZipArchive opened = SafeZipArchive.openDetected(zip, "legacy zip")) {
            assertThat(opened.zip().getEntry("книга.fb2")).isNotNull();
        }
    }

    @Test
    void rejectsZipReachedThroughSymlinkedParent() throws Exception {
        Path real = Files.createDirectories(temp.resolve("real"));
        Path zip = real.resolve("books.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("book.fb2"));
            out.write("book".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        assertThatThrownBy(() -> SafeZipArchive.open(
                alias.resolve("books.zip"), StandardCharsets.UTF_8, "test zip"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
    }
}
