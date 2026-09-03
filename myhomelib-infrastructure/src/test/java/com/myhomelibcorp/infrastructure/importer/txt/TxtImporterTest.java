package com.myhomelibcorp.infrastructure.importer.txt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TxtImporterTest {
    @TempDir Path tempDir;

    @Test
    void fallsBackFromMalformedUtf8ToWindows1251() throws Exception {
        Path file = tempDir.resolve("legacy.txt");
        Files.write(file, "Заголовок книги\nТекст".getBytes(Charset.forName("Windows-1251")));

        var books = new TxtImporter().importBooks(file).toList();
        assertThat(books).hasSize(1);
        assertThat(books.getFirst().getTitle()).isEqualTo("Заголовок книги");
    }

    @Test
    void ioFailureIsNotReportedAsSuccessfulUnknownBook() throws Exception {
        Path unreadable = tempDir.resolve("broken.txt");
        Files.createDirectory(unreadable);

        assertThatThrownBy(() -> new TxtImporter().importBooks(unreadable).toList())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Помилка імпорту TXT");
    }
}
