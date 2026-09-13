package com.myhomelibcorp.application.annotation.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfAnnotationSelectionDataTest {
    @Test
    void keepsPageLocalCoordinatesExplicit() {
        PdfAnnotationSelectionData selection = new PdfAnnotationSelectionData(
                "book-1", "artifact-1", 4, 10, 18, "selected", "before", "after");

        assertThat(selection.pageIndex()).isEqualTo(4);
        assertThat(selection.pageTextStartOffset()).isEqualTo(10);
        assertThat(selection.pageTextEndOffset()).isEqualTo(18);
        assertThat(selection.hasTextRange()).isTrue();
    }

    @Test
    void rejectsInvalidPageLocalRange() {
        assertThatThrownBy(() -> new PdfAnnotationSelectionData(
                "book-1", null, 0, 9, 3, "x", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
