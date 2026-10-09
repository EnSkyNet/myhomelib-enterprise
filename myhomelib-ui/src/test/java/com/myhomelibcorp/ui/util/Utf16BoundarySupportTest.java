package com.myhomelibcorp.ui.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Utf16BoundarySupportTest {
    @Test
    void keepsOrdinaryCutPoint() {
        assertThat(Utf16BoundarySupport.safeCut("abcdef", 3)).isEqualTo(3);
    }

    @Test
    void movesCutBeforeSurrogatePair() {
        String value = "ab😀cd";
        assertThat(Utf16BoundarySupport.safeCut(value, 3)).isEqualTo(2);
    }

    @Test
    void movesCutBeforeBaseCharacterWhenNextCodePointIsCombiningMark() {
        String value = "ab" + "e\u0301" + "cd";
        assertThat(Utf16BoundarySupport.safeCut(value, 3)).isEqualTo(2);
    }

    @Test
    void clampsRequestedEndToTextLength() {
        assertThat(Utf16BoundarySupport.safeCut("abc", 99)).isEqualTo(3);
        assertThat(Utf16BoundarySupport.safeCut(null, 99)).isZero();
        assertThat(Utf16BoundarySupport.safeCut("abc", -1)).isZero();
    }
}
