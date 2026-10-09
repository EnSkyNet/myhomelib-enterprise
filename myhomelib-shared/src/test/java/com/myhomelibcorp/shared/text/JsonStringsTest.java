package com.myhomelibcorp.shared.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonStringsTest {
    @Test
    void escapesJsonControlCharactersDeterministically() {
        assertThat(JsonStrings.escape("a\"\\\b\f\n\r\t\u0001z"))
                .isEqualTo("a\\\"\\\\\\b\\f\\n\\r\\t\\u0001z");
    }

    @Test
    void nullAndQuoteAreSafe() {
        assertThat(JsonStrings.escape(null)).isEmpty();
        assertThat(JsonStrings.quote("x\ny")).isEqualTo("\"x\\ny\"");
    }
}
