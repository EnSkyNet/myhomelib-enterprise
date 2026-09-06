package com.myhomelibcorp.application.query.book;

import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BookFormatRegistryConsistencyTest {
    @Test
    void everyRegistrySearchFormatHasCanonicalBookFormat() {
        SupportedFormatRegistry.standard().all().forEach(format ->
                assertThatCode(() -> BookFormat.valueOf(format.searchFormat()))
                        .as(format.id() + " -> " + format.searchFormat())
                        .doesNotThrowAnyException());
    }

    @Test
    void representativeFormatsAreNotCollapsedToUnknown() {
        var registry = SupportedFormatRegistry.standard();
        assertThat(registry.searchFormat("book.docx")).isEqualTo("DOCX");
        assertThat(registry.searchFormat("book.txt")).isEqualTo("TXT");
        assertThat(registry.searchFormat("book.html")).isEqualTo("HTML");
        assertThat(registry.searchFormat("book.pdf")).isEqualTo("PDF");
        assertThat(registry.searchFormat("book.mobi")).isEqualTo("MOBI");
        assertThat(registry.searchFormat("book.azw3")).isEqualTo("AZW3");
        assertThat(registry.searchFormat("book.djvu")).isEqualTo("DJVU");
    }
}
