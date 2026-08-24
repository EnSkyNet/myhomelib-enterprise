package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookQueryBuilderStage5Test {

    private final BookQueryBuilder builder = new BookQueryBuilder();

    @Test
    void alreadyReadUsesExistingProgressContract() {
        var sql = builder.build(BookQuery.builder().onlyRead(true).build());
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("b.progress = 100");
        assertThat(normalized).contains("LIMIT ? OFFSET ?");
    }

    @Test
    void historyUsesDedicatedTableAndLastOpenedOrder() {
        var sql = builder.build(BookQuery.builder().onlyInHistory(true).build());
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("JOIN reading_history rh ON rh.book_id = b.id");
        assertThat(normalized).contains("ORDER BY rh.last_opened_at DESC, b.id ASC");
        assertThat(normalized).contains("LIMIT ? OFFSET ?");
    }

    @Test
    void historyCountUsesSameDedicatedMembership() {
        var sql = builder.buildCount(BookQuery.builder().onlyInHistory(true).build());
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("JOIN reading_history rh ON rh.book_id = b.id");
        assertThat(normalized).contains("b.deleted = 0");
        assertThat(normalized).doesNotContain("ORDER BY");
    }
}
