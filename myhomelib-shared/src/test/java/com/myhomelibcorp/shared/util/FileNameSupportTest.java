package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileNameSupportTest {

    @Test
    void decodesLegacyHashUnicodeEscapesWithoutChangingAsciiEscapes() {
        assertThat(FileNameSupport.decodeHashUnicodeEscapes(
                "#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2"))
                .isEqualTo("Возвращение_1.fb2");
        assertThat(FileNameSupport.decodeHashUnicodeEscapes("literal-#U0041.fb2"))
                .isEqualTo("literal-#U0041.fb2");
        assertThat(FileNameSupport.decodeHashUnicodeEscapes("broken-#U04ZZ.fb2"))
                .isEqualTo("broken-#U04ZZ.fb2");
    }

    @Test
    void decodesPairedUtf16SurrogatesButPreservesUnpairedOnes() {
        assertThat(FileNameSupport.decodeHashUnicodeEscapes("book-#UD83D#UDE00.fb2"))
                .isEqualTo("book-😀.fb2");
        assertThat(FileNameSupport.decodeHashUnicodeEscapes("book-#UD83D.fb2"))
                .isEqualTo("book-#UD83D.fb2");
    }
}
