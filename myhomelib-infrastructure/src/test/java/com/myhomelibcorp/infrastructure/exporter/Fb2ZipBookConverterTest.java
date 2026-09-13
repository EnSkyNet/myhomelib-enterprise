package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2ZipBookConverterTest {
    @TempDir Path temp;

    @Test
    void createsRealZipArchiveWithFb2Entry() throws Exception {
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Демон")
                .file(new BookFile("demon.fb2", "", "", 4, temp.toString()))
                .local(true)
                .build();
        Path target = temp.resolve("demon.fb2.zip");

        new Fb2ZipBookConverter().convert(book,
                new ByteArrayInputStream("<FictionBook/>".getBytes(StandardCharsets.UTF_8)), target);

        assertThat(target).exists();
        assertThat(Files.size(target)).isGreaterThan(0);
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertThat(zip.size()).isEqualTo(1);
            assertThat(zip.getEntry("demon.fb2")).isNotNull();
        }
    }
}
