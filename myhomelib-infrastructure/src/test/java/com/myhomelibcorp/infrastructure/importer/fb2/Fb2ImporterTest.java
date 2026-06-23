package com.myhomelibcorp.infrastructure.importer.fb2;

import com.myhomelibcorp.domain.model.book.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2ImporterTest {

    @TempDir
    Path tempDir;

    @Test
    void importsBasicTitleInfoFromFb2() throws Exception {
        Path file = tempDir.resolve("sample.fb2");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook>
                  <description>
                    <title-info>
                      <genre>sf</genre>
                      <author>
                        <first-name>Іван</first-name>
                        <middle-name>Петрович</middle-name>
                        <last-name>Автор</last-name>
                      </author>
                      <book-title>Тестова книга</book-title>
                      <annotation><p>Короткий опис.</p></annotation>
                      <keywords>тест</keywords>
                      <lang>uk</lang>
                      <sequence name="Серія" number="2"/>
                    </title-info>
                  </description>
                </FictionBook>
                """, StandardCharsets.UTF_8);

        Fb2Importer importer = new Fb2Importer();
        List<Book> books = importer.importBooks(file).toList();

        assertThat(books).hasSize(1);
        Book book = books.get(0);
        assertThat(book.getTitle()).isEqualTo("Тестова книга");
        assertThat(book.getAuthors()).hasSize(1);
        assertThat(book.getAuthors().get(0).getFullName()).isEqualTo("Автор Іван Петрович");
        assertThat(book.getGenres()).extracting(genre -> genre.getId().asString()).containsExactly("sf");
        assertThat(book.getSeries()).isEqualTo("Серія");
        assertThat(book.getSequenceNumber()).isEqualTo(2);
        assertThat(book.getLanguage().toString()).isEqualTo("uk");
        assertThat(book.getAnnotation()).isEqualTo("Короткий опис.");
    }
}
