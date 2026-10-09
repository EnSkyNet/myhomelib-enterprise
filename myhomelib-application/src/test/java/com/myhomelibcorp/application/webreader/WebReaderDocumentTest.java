package com.myhomelibcorp.application.webreader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebReaderDocumentTest {
    @Test
    void nonFiniteResumePercentIsSanitized() {
        WebReaderDocument document = new WebReaderDocument(
                "b", "Book", "epub", true, "", List.of(), 0, 0, 0, Double.NaN);
        assertThat(document.resumePercent()).isZero();
    }
}
