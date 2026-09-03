package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordIndexSupportTest {

    @Test
    void tokenizesNormalizesAndDeduplicatesUnicodeKeywords() {
        var tokens = KeywordIndexSupport.tokenize("  Science Fiction ; SCIENCE   FICTION | Україна, Ｊａｖａ ");

        assertThat(tokens)
                .extracting(KeywordIndexSupport.KeywordToken::normalizedName)
                .containsExactly("science fiction", "україна", "java");
        assertThat(tokens)
                .extracting(KeywordIndexSupport.KeywordToken::displayName)
                .containsExactly("Science Fiction", "Україна", "Java");
    }

    @Test
    void normalizesNonBreakingWhitespaceAndCompatibilityCharacters() {
        assertThat(KeywordIndexSupport.normalizeKeyword("  Ｓｃｉｅｎｃｅ\u00a0  Fiction "))
                .isEqualTo("science fiction");
    }
}
