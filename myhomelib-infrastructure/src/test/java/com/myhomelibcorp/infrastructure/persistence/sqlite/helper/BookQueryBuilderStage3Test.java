package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
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
    void titleCursorRetainsSelectedGroupMembershipFilter() {
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(37L))
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(100, 100))
                .sortBy(com.myhomelibcorp.application.query.common.SortBy.TITLE)
                .direction(com.myhomelibcorp.application.query.common.SortDirection.ASC)
                .build();

        var sql = builder.buildTitleCursor(query,
                new BookPageCursor("Title 99", "book-99"), BookPageDirection.AFTER);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("JOIN book_groups qbg_group ON qbg_group.book_id = b.id");
        assertThat(normalized).contains("qbg_group.group_id = ?");
        assertThat(normalized).contains("(b.title, b.id) > (?, ?)");
        assertThat(normalized).doesNotContain("OFFSET");
        assertThat(Arrays.asList(sql.params())).containsExactly(37L, "Title 99", "book-99", 100);
    }

    @Test
    void groupExportLoadsFullRecordsAndIncludesDeletedMembershipWithoutChangingBrowsingDefaults() {
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(37L))
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(100, 0))
                .sortBy(com.myhomelibcorp.application.query.common.SortBy.TITLE)
                .direction(com.myhomelibcorp.application.query.common.SortDirection.ASC)
                .build();

        var page = builder.buildGroupExportPage(query);
        var count = builder.buildGroupExportCount(query);
        String pageSql = page.sql().replaceAll("\\s+", " ");
        String countSql = count.sql().replaceAll("\\s+", " ");

        assertThat(pageSql).startsWith("SELECT b.* FROM books b");
        assertThat(pageSql).contains("qbg_group.group_id = ?");
        assertThat(pageSql).doesNotContain("b.deleted = 0");
        assertThat(pageSql).contains("LIMIT ? OFFSET ?");
        assertThat(countSql).startsWith("SELECT COUNT(*) FROM books b");
        assertThat(countSql).contains("qbg_group.group_id = ?");
        assertThat(countSql).doesNotContain("b.deleted = 0");

        // The public browsing query must continue hiding tombstones.
        assertThat(builder.build(query).sql()).contains("b.deleted = 0");
    }

    @Test
    void groupExportTitleCursorUsesFullRecordsAndKeepsDeletedMembership() {
        BookQuery query = BookQuery.builder()
                .groupId(GroupId.fromLong(37L))
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(100, 100))
                .sortBy(com.myhomelibcorp.application.query.common.SortBy.TITLE)
                .direction(com.myhomelibcorp.application.query.common.SortDirection.ASC)
                .build();

        var sql = builder.buildGroupExportTitleCursor(query,
                new BookPageCursor("Title 99", "book-99"), BookPageDirection.AFTER);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).startsWith("SELECT b.* FROM books b");
        assertThat(normalized).contains("qbg_group.group_id = ?");
        assertThat(normalized).contains("(b.title, b.id) > (?, ?)");
        assertThat(normalized).doesNotContain("b.deleted = 0");
        assertThat(normalized).doesNotContain("OFFSET");
        assertThat(Arrays.asList(sql.params())).containsExactly(37L, "Title 99", "book-99", 100);
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
