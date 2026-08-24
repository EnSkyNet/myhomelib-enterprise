package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BookQueryBuilderStage3Test {

    private final BookQueryBuilder builder = new BookQueryBuilder();

    @Test
    void buildsYearLanguageAndArchiveFiltersWithoutDroppingPagination() {
        BookQuery query = BookQuery.builder()
                .year(2024)
                .language(LanguageCode.of("uk"))
                .archive("D:\\Library", "D:\\Library\\packs\\books.zip")
                .build();

        var sql = builder.build(query);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("LOWER(TRIM(b.language)) = LOWER(?)");
        assertThat(normalized).contains("b.year = ?");
        assertThat(normalized).contains("COALESCE(b.archive_entry, '') <> ''");
        assertThat(normalized).contains("LOWER(REPLACE(COALESCE(b.folder, ''), '\\', '/')) = LOWER(?)");
        assertThat(normalized).contains("LIMIT ? OFFSET ?");
        assertThat(Arrays.asList(sql.params()))
                .contains("uk", 2024, "D:/Library/packs/books.zip", "D:/Library");
    }
}
