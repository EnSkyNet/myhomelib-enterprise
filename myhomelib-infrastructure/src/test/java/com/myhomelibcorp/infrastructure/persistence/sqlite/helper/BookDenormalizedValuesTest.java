package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookDenormalizedValuesTest {
    @Test
    void usesCanonicalRegistryFormatsForSqliteFilterColumn() {
        assertThat(BookDenormalizedValues.format("book.FB2.ZIP")).isEqualTo("ZIP");
        assertThat(BookDenormalizedValues.format("book.epub")).isEqualTo("EPUB");
        assertThat(BookDenormalizedValues.format("book.pdf")).isEqualTo("PDF");
        assertThat(BookDenormalizedValues.format("book.DOCX")).isEqualTo("DOCX");
        assertThat(BookDenormalizedValues.format("book.AZW3")).isEqualTo("AZW3");
        assertThat(BookDenormalizedValues.format("book.DJVU")).isEqualTo("DJVU");
        assertThat(BookDenormalizedValues.format("book.bin")).isEqualTo("UNKNOWN");
    }
}
