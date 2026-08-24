package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookQuery;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BookQueryBuilderStage4Test {

    private final BookQueryBuilder builder = new BookQueryBuilder();

    @Test
    void buildsExactKeywordAndRatedReviewedFiltersWithoutDroppingPagination() {
        BookQuery query = BookQuery.builder()
                .keyword("science fiction")
                .onlyRated(true)
                .onlyReviewed(true)
                .build();

        var sql = builder.build(query);
        String normalized = sql.sql().replaceAll("\\s+", " ");

        assertThat(normalized).contains("WITH RECURSIVE split");
        assertThat(normalized).contains("LOWER(token) = LOWER(?)");
        assertThat(normalized).contains("COALESCE(b.rate, 0) > 0");
        assertThat(normalized).contains("b.review IS NOT NULL AND TRIM(b.review) <> ''");
        assertThat(normalized).contains("LIMIT ? OFFSET ?");
        assertThat(Arrays.asList(sql.params())).contains("science fiction");
    }
}
