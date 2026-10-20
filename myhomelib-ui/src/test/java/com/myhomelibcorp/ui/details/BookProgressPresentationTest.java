package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BookProgressPresentationTest {
    @Test void carriesVisibleProgressWhenDetailsProjectionIsZero() {
        var loaded = BookDto.builder().id("same").progress(0).title("Full metadata").build();
        var selected = BookDto.builder().id("same").progress(21).build();
        var result = BookProgressPresentation.preserveVisibleProgress(loaded, selected);
        assertThat(result.getProgress()).isEqualTo(21);
        assertThat(result.getTitle()).isEqualTo("Full metadata");
        assertThat(loaded.getProgress()).isZero();
    }
    @Test void retainsStoredNonzeroProgressAndRejectsOtherBook() {
        var loaded = BookDto.builder().id("a").progress(65).build();
        var selected = BookDto.builder().id("a").progress(21).build();
        assertThat(BookProgressPresentation.preserveVisibleProgress(loaded, selected)).isSameAs(loaded);
        var other = BookDto.builder().id("b").progress(0).build();
        assertThat(BookProgressPresentation.preserveVisibleProgress(other, selected)).isSameAs(other);
    }
    @Test void zeroOrNullSelectionDoesNotAlterBook() {
        var loaded = BookDto.builder().id("a").progress(0).build();
        assertThat(BookProgressPresentation.preserveVisibleProgress(loaded, null)).isSameAs(loaded);
        assertThat(BookProgressPresentation.preserveVisibleProgress(loaded,
                BookDto.builder().id("a").progress(0).build())).isSameAs(loaded);
    }
}
