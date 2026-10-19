package com.myhomelibcorp.application.usecase.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fb2ZipExportNameNormalizerTest {
    @TempDir Path temp;

    @Test
    void renamesOnlineLibraryMemberWithoutChangingFb2ContentOrOriginalArchive() throws Exception {
        String technical = "Dornichev_Demon-Pozhiratel-dush-01-18.729586.fb2";
        byte[] content = "<FictionBook><description>текст книги</description></FictionBook>"
                .getBytes(StandardCharsets.UTF_8);
        Path original = createZip("original.fb2.zip", List.of(technical), content);
        byte[] originalZip = Files.readAllBytes(original);
        String friendly = "Демон. Пожиратель душ #01-18";

        Path normalized = Fb2ZipExportNameNormalizer.normalize(
                original, temp.resolve(friendly + ".fb2.zip"), temp, () -> false);

        assertThat(normalized).isNotEqualTo(original);
        assertThat(Files.readAllBytes(original)).isEqualTo(originalZip);
        try (ZipFile zip = new ZipFile(normalized.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.size()).isEqualTo(1);
            ZipEntry renamed = zip.getEntry(friendly + ".fb2");
            assertThat(renamed).isNotNull();
            assertThat(zip.getInputStream(renamed).readAllBytes()).isEqualTo(content);
            assertThat(zip.getEntry(technical)).isNull();
        }
    }

    @Test
    void keepsAlreadyNormalizedArchiveWithoutRecompression() throws Exception {
        Path existing = createZip("existing.fb2.zip", List.of("Book.fb2"),
                "<FictionBook/>".getBytes(StandardCharsets.UTF_8));
        Path result = Fb2ZipExportNameNormalizer.normalize(
                existing, temp.resolve("Book.fb2.zip"), temp, () -> false);
        assertThat(result).isEqualTo(existing);
        assertThat(temp.resolve("normalized-entry.fb2.zip")).doesNotExist();
    }

    @Test
    void refusesToDiscardAdditionalArchiveMembers() throws Exception {
        Path multi = createZip("many.fb2.zip", List.of("book.fb2", "another.fb2"),
                "<FictionBook/>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Fb2ZipExportNameNormalizer.normalize(
                multi, temp.resolve("Renamed.fb2.zip"), temp, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exactly one");
        assertThat(temp.resolve("normalized-entry.fb2.zip")).doesNotExist();
    }

    @Test
    void doesNotCreateOutputOnCancelledExport() throws Exception {
        Path original = createZip("cancel.fb2.zip", List.of("old.fb2"),
                "<FictionBook/>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Fb2ZipExportNameNormalizer.normalize(
                original, temp.resolve("new.fb2.zip"), temp, () -> true))
                .isInstanceOf(CancellationException.class);
        assertThat(temp.resolve("normalized-entry.fb2.zip")).doesNotExist();
    }

    private Path createZip(String filename, List<String> entries, byte[] content) throws IOException {
        Path path = temp.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return path;
    }
}
