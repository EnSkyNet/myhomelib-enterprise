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
}
