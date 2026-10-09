package com.myhomelibcorp.application.util;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookIdListSupportTest {
    private static BookId id(String value) { return BookId.fromString(value); }

    @Test
    void keepsFirstOccurrenceOrderAndDropsNullsAndDuplicates() {
        BookId first = id("00000000-0000-0000-0000-000000000001");
        BookId second = id("00000000-0000-0000-0000-000000000002");

        assertThat(BookIdListSupport.stableDistinctIgnoringNulls(List.of(first, second, first)))
                .containsExactly(first, second);
        assertThat(BookIdListSupport.stableDistinctIgnoringNulls(null)).isEmpty();
    }

    @Test
    void boundedVariantFailsAsSoonAsUniqueLimitIsExceeded() {
        assertThatThrownBy(() -> BookIdListSupport.stableDistinctIgnoringNulls(
                java.util.Arrays.asList(id("00000000-0000-0000-0000-000000000001"), null, id("00000000-0000-0000-0000-000000000001"), id("00000000-0000-0000-0000-000000000002"), id("00000000-0000-0000-0000-000000000003")), 2, "selection limit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selection limit");
    }
}
