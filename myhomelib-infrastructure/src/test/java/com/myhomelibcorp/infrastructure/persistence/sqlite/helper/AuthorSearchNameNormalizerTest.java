package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthorSearchNameNormalizerTest {

    @Test
    void normalizesCyrillicAndCanonicalNameOrder() {
        assertEquals("шевченко тарас григорович",
                AuthorSearchNameNormalizer.normalize(" Тарас ", "ГРИГОРОВИЧ", "ШЕВЧЕНКО"));
    }

    @Test
    void skipsNullAndBlankParts() {
        assertEquals("іваненко іван", AuthorSearchNameNormalizer.normalize("ІВАН", null, " ІВАНЕНКО "));
        assertEquals("", AuthorSearchNameNormalizer.normalize(" ", null, ""));
    }

    @Test
    void normalizesUnicodeQueryForUkrainianAndRussianLetters() {
        assertEquals("боярский", AuthorSearchNameNormalizer.normalizeQuery("БОЯРСКИЙ"));
        assertEquals("іїєґ ёй", AuthorSearchNameNormalizer.normalizeQuery("  ІЇЄҐ\u00A0 ЁЙ  "));
    }

    @Test
    void normalizesCompatibilityUnicodeForms() {
        assertEquals("иванов", AuthorSearchNameNormalizer.normalizeQuery("Иванов"));
        assertEquals("ff", AuthorSearchNameNormalizer.normalizeQuery("ＦＦ"));
    }
    @Test
    void tokenizesNameQueryIndependentlyOfFirstLastNameOrder() {
        assertEquals(java.util.List.of("дмитрий", "дорничев"),
                AuthorSearchNameNormalizer.normalizeQueryTokens("Дмитрий Дорничев", 8));
        assertEquals(java.util.List.of("дорничев", "дмитрий"),
                AuthorSearchNameNormalizer.normalizeQueryTokens("Дорничев Дмитрий", 8));
    }

}
