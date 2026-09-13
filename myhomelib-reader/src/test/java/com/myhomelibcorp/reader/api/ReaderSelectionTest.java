package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReaderSelectionTest {
    @Test
    void validatesSourceRangeAndContext() {
        ReaderSelection selection = new ReaderSelection(
                10, 15, "quote", 1, "c1", "Chapter", 2, "p:2", 0.25, "before", "after");

        assertThat(selection.length()).isEqualTo(5);
        assertThat(selection.chapterId()).isEqualTo("c1");
        assertThat(selection.paragraphId()).isEqualTo("p:2");
    }

    @Test
    void rejectsEmptyOrBlankSelections() {
        assertThatThrownBy(() -> new ReaderSelection(
                5, 5, "x", 0, null, null, -1, null, 0, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReaderSelection(
                5, 6, " ", 0, null, null, -1, null, 0, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
