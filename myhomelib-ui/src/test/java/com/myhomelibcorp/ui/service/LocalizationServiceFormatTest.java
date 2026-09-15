package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizationServiceFormatTest {

    @Test
    void supportsPrintfAndMessageFormatCatalogPlaceholders() {
        assertThat(LocalizationService.formatPattern("Колекція: {0}", "Моя бібліотека"))
                .isEqualTo("Колекція: Моя бібліотека");
        assertThat(LocalizationService.formatPattern("Знайдено %d книг", 12))
                .isEqualTo("Знайдено 12 книг");
        assertThat(LocalizationService.formatPattern("Smart-колекція ''{0}'': {1} книг", "Sci-Fi", 7))
                .isEqualTo("Smart-колекція 'Sci-Fi': 7 книг");
    }
}
