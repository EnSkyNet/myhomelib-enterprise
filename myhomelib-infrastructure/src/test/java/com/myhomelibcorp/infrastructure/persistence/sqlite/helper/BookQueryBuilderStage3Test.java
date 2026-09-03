package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
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

        assertThat(normalized).contains("LOWER(TRIM(COALESCE(b.language, ''))) = LOWER(TRIM(?))");
        assertThat(normalized).contains("b.year = ?");
        assertThat(normalized).contains("COALESCE(b.archive_entry, '') <> ''");
        assertThat(normalized).contains("LOWER(REPLACE(COALESCE(b.folder, ''), '\\', '/')) = LOWER(?)");
        assertThat(normalized).contains("LIMIT ? OFFSET ?");
        assertThat(Arrays.asList(sql.params()))
                .contains("uk", 2024, "D:/Library/packs/books.zip", "D:/Library");
    }
    @Test
    void titleCursorPageUsesKeysetWithoutOffset() {
        BookQuery query = BookQuery.builder()
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(50, 450_000))
                .sortBy(com.myhomelibcorp.application.query.common.SortBy.TITLE)
                .direction(com.myhomelibcorp.application.query.common.SortDirection.ASC)
                .build();

        var sql = builder.buildTitleCursor(query, new BookPageCursor("Book 449999", "b449999"), BookPageDirection.AFTER);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("(b.title, b.id) > (?, ?)");
        assertThat(normalized).contains("ORDER BY b.title ASC, b.id ASC LIMIT ?");
        assertThat(normalized).doesNotContain("OFFSET");
        assertThat(Arrays.asList(sql.params())).containsExactly("Book 449999", "b449999", 50);
    }

    @Test
    void previousTitleCursorScansOppositeDirectionForStableReverse() {
        BookQuery query = BookQuery.builder()
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(50, 50))
                .sortBy(com.myhomelibcorp.application.query.common.SortBy.TITLE)
                .direction(com.myhomelibcorp.application.query.common.SortDirection.DESC)
                .build();

        var sql = builder.buildTitleCursor(query, new BookPageCursor("Book 050", "b050"), BookPageDirection.BEFORE);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("(b.title, b.id) > (?, ?)");
        assertThat(normalized).contains("ORDER BY b.title ASC, b.id ASC LIMIT ?");
        assertThat(normalized).doesNotContain("OFFSET");
    }

}
