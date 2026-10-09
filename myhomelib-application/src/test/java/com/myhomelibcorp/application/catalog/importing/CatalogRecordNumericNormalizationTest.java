package com.myhomelibcorp.application.catalog.importing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRecordNumericNormalizationTest {
    @Test
    void nonFiniteSequenceAndRatingAreDiscardedAtCatalogBoundary() {
        CatalogRecord record = new CatalogRecord(
                "id", "Title", null, "Series", Double.POSITIVE_INFINITY,
                null, "uk", "fb2", "book.fb2", "", "", 10L, false,
                "", "", null, "", null, "", null,
                Double.NaN, null, null, null, null, null);

        assertThat(record.sequence()).isNull();
        assertThat(record.rating()).isNull();
    }

    @Test
    void finiteSequenceAndRatingArePreserved() {
        CatalogRecord record = new CatalogRecord(
                "id", "Title", null, "Series", 2.5,
                null, "uk", "fb2", "book.fb2", "", "", 10L, false,
                "", "", null, "", null, "", null,
                4.5, null, null, null, null, null);

        assertThat(record.sequence()).isEqualTo(2.5);
        assertThat(record.rating()).isEqualTo(4.5);
    }
}
