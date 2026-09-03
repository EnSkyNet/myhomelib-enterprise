package com.myhomelibcorp.ui.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryFactoryTest {

    @Test
    void advancedRequestAndSavedQueryComeFromSameSnapshot() {
        SearchFormInput form = new SearchFormInput(
                "space", "Empire", "Doe OR Roe", "Saga", "sf", "magic", "annotation", "book.fb2",
                "uk", "2", "5", "1990", "2026",
                LocalDate.of(2020, 1, 2), LocalDate.of(2026, 9, 3), true);

        var request = SearchQueryFactory.advanced(form, 500, 1000);
        assertThat(request.text()).contains("space", "title:\"Empire\"", "authors:\"Doe\" OR authors:\"Roe\"");
        assertThat(request.language().value()).isEqualTo("uk");
        assertThat(request.ratingFrom()).isEqualTo(2);
        assertThat(request.ratingTo()).isEqualTo(5);
        assertThat(request.yearFrom()).isEqualTo(1990);
        assertThat(request.yearTo()).isEqualTo(2026);
        assertThat(request.localOnly()).isTrue();
        assertThat(request.offset()).isEqualTo(1000);

        String saved = SearchQueryFactory.savedQuery(form);
        assertThat(saved).contains("language:\"uk\"", "library_rate:[2 TO 5]", "year:[1990 TO 2026]",
                "created:[20200102 TO 20260903]", "local:1");
    }

    @Test
    void malformedOptionalNumbersAndLanguageDoNotBreakSearch() {
        SearchFormInput form = new SearchFormInput(
                "", "", "", "", "", "", "", "", "***", "x", "", "bad", "",
                null, null, false);
        var request = SearchQueryFactory.advanced(form, 500, 0);
        assertThat(request.language()).isNull();
        assertThat(request.ratingFrom()).isNull();
        assertThat(request.yearFrom()).isNull();
    }
}
