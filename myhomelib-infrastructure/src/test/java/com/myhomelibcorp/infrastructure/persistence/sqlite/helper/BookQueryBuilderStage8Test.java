package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.query.book.BookFormat;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.SortBy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookQueryBuilderStage8Test {

    private final BookQueryBuilder builder = new BookQueryBuilder();

    @Test
    void unifiedAndFilterIsAppliedBeforePagingAndCountUsesSameClause() {
        BookFilterSpec filter = new BookFilterSpec(
                BookFilterMode.AND, "uk", 2010, 2026, BookFormat.FB2,
                true, false, 2, 5, true, BookQuickFilterField.TITLE, "істор");
        BookQuery query = BookQuery.builder().filterSpec(filter).build();

        var page = builder.build(query);
        var count = builder.buildCount(query);
        String pageSql = normalize(page.sql());
        String countSql = normalize(count.sql());

        assertThat(pageSql).contains("LOWER(TRIM(COALESCE(b.language, ''))) = LOWER(?)");
        assertThat(pageSql).contains("b.year BETWEEN ? AND ?");
        assertThat(pageSql).contains("UPPER(COALESCE(b.format, '')) = ?");
        assertThat(pageSql).contains("COALESCE(b.local, 0) = ?");
        assertThat(pageSql).contains("COALESCE(b.progress, 0) < 100");
        assertThat(pageSql).contains("COALESCE(b.rate, 0) BETWEEN ? AND ?");
        assertThat(pageSql).contains("LOWER(COALESCE(b.title, '')) LIKE ?");
        assertThat(pageSql).contains("LIMIT ? OFFSET ?");
        assertThat(countSql).contains("LOWER(COALESCE(b.title, '')) LIKE ?");
        assertThat(countSql).doesNotContain("LIMIT ? OFFSET ?");
    }

    @Test
    void unifiedOrModeBuildsOneOrGroupStillAndedWithDeletedGuard() {
        BookFilterSpec filter = new BookFilterSpec(
                BookFilterMode.OR, "en", 2020, null, null,
                null, true, null, null, false, BookQuickFilterField.ANY, "space");

        String sql = normalize(builder.build(BookQuery.builder().filterSpec(filter).build()).sql());
        assertThat(sql).contains("b.deleted = 0 AND (");
        assertThat(sql).contains(" OR ");
        assertThat(sql).contains("COALESCE(b.progress, 0) >= 100");
        assertThat(sql).contains("EXISTS (SELECT 1 FROM book_authors");
    }

    @Test
    void serverAuthorSortUsesDenormalizedStableColumn() {
        String sql = normalize(builder.build(BookQuery.builder().sortBy(SortBy.AUTHOR).build()).sql());
        assertThat(sql).contains("ORDER BY b.author_sort ASC, b.id ASC");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
