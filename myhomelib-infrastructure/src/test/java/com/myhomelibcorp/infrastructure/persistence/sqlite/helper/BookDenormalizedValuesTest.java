package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookDenormalizedValuesTest {
    @Test
    void detectsSupportedFormatsIncludingFb2Zip() {
        assertThat(BookDenormalizedValues.format("book.FB2.ZIP")).isEqualTo("FB2ZIP");
        assertThat(BookDenormalizedValues.format("book.epub")).isEqualTo("EPUB");
        assertThat(BookDenormalizedValues.format("book.pdf")).isEqualTo("PDF");
        assertThat(BookDenormalizedValues.format("book.bin")).isEqualTo("UNKNOWN");
    }
}
