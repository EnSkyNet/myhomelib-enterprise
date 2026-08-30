package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadMacroResolverTest {

    @Test
    void expandsUpstreamBookFieldsAndPathAliases() throws Exception {
        BookDto book = BookDto.builder()
                .id("42").title("Назва").series("Серія").language("uk")
                .fileName("book.fb2").folder("daily\\2026.zip").archiveEntry("texts\\book.fb2")
                .fileSize(1234).rate(4).sequenceNumber(7).progress(65).libraryRate(5)
                .keywords("one,two").annotation("опис").publisher("Pub").city("Kyiv")
                .year(2026).isbn("978-0").libId("lib-42").build();
        Collection collection = new Collection("c1", "Online", Path.of("C:/library"), "db.sqlite", 1,
                "reader", null, "https://example.test/books", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(
                book, collection, Path.of("C:/library"), "daily\\2026.zip", "secret");

        String result = resolver.expand(
                "%ID%|%TITLE%|%SERIES%|%LANG%|%FILENAME%|%FOLDER%|%ARCHIVE%|%ARCHIVEENTRY%|%EXT%|%COLLECTIONROOT%|%USER%|%PASS%|%RESURL%",
                "https://example.test/final");

        assertThat(result).isEqualTo(
                "42|Назва|Серія|uk|book.fb2|daily/2026.zip|daily/2026.zip|texts/book.fb2|fb2|C:/library|reader|secret|https://example.test/final");
    }

    @Test
    void replacementValuesAreNeverReparsedAsNestedMacros() throws Exception {
        BookDto book = BookDto.builder().id("1").fileName("a.txt").build();
        Collection collection = new Collection("c", "C", Path.of("."), "c.db", 1,
                "reader", null, "https://safe.example", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(book, collection, Path.of("."), "a.txt", "%URL%");

        assertThat(resolver.expand("x=%PASS%", null)).isEqualTo("x=%URL%");
    }

    @Test
    void rejectsControlCharactersProducedByMacros() {
        BookDto book = BookDto.builder().id("1").fileName("a.txt").title("bad\nheader").build();
        Collection collection = new Collection("c", "C", Path.of("."), "c.db", 1,
                null, null, "https://safe.example", "", "");
        DownloadMacroResolver resolver = new DownloadMacroResolver(book, collection, Path.of("."), "a.txt", "");

        assertThatThrownBy(() -> resolver.expand("%TITLE%", null))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageContaining("control characters");
    }
}
