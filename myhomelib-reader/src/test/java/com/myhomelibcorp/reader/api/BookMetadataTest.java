package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookMetadataTest {

    @Test
    void emptyMetadataIsActuallyEmptyAndLanguageNeutral() {
        BookMetadata metadata = BookMetadata.empty();

        assertThat(metadata.isEmpty()).isTrue();
        assertThat(metadata.title()).isEmpty();
        assertThat(metadata.authors()).isEmpty();
        assertThat(metadata.language()).isEqualTo("und");
    }
}
