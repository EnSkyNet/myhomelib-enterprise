package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadMacroResolverTest {

    @Test
    void expandsBookFieldsAndPathAliases() throws Exception {
        BookDto book = BookDto.builder()
                .id("42").title("Назва").series("Серія").language("uk")
                .fileName("book.fb2").folder("daily\\2026.zip").archiveEntry("texts\\book.fb2")
                .fileSize(1234).rate(4).sequenceNumber(7).progress(65).libraryRate(5)
                .keywords("one,two").annotation("опис").publisher("Pub").city("Kyiv")
                .year(2026).isbn("978-0").libId("lib-42")
                .authors(List.of(AuthorDto.builder()
                        .lastName("Романович")
                        .firstName("Роман")
                        .fullName("Романович Роман")
                        .build()))
                .build();
        Collection collection = new Collection("c1", "Online", Path.of("C:/library"), "db.sqlite", 1,
                "reader", null, "https://example.test/books", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(
                book, collection, Path.of("C:/library"), "daily\\2026.zip", "secret");

        String result = resolver.expand(
                "%ID%|%TITLE%|%SERIES%|%LANG%|%FILENAME%|%FOLDER%|%ARCHIVE%|%ARCHIVEENTRY%|%EXT%|%COLLECTIONROOT%|%USER%|%PASS%|%RESURL%|%b%",
                "https://example.test/final");

        // Перевіряємо, що %b% замінюється на першу літеру автора
        assertThat(result).contains("Р");
        assertThat(result).isEqualTo(
                "42|Назва|Серія|uk|book.fb2|daily/2026.zip|daily/2026.zip|texts/book.fb2|fb2|C:/library|reader|secret|https://example.test/final|Р");
    }

    @Test
    void expandsBMacroWithFirstLetterOfAuthor() throws Exception {
        // Тест з автором "Шевченко Тарас"
        BookDto book = BookDto.builder()
                .id("1")
                .title("Кобзар")
                .authors(List.of(AuthorDto.builder()
                        .lastName("Шевченко")
                        .firstName("Тарас")
                        .fullName("Шевченко Тарас")
                        .build()))
                .build();
        Collection collection = new Collection("c", "C", Path.of("."), "c.db", 1, null, null, "https://example.test", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(book, collection, Path.of("."), "a.txt", "");

        assertThat(resolver.expand("%b%", null)).isEqualTo("Ш");
    }

    @Test
    void expandsBMacroWithUnderscoreWhenAuthorMissing() throws Exception {
        BookDto book = BookDto.builder()
                .id("1")
                .title("Без автора")
                .authors(List.of())
                .authorsText("")
                .build();
        Collection collection = new Collection("c", "C", Path.of("."), "c.db", 1, null, null, "https://example.test", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(book, collection, Path.of("."), "a.txt", "");

        assertThat(resolver.expand("%b%", null)).isEqualTo("_");
    }
}