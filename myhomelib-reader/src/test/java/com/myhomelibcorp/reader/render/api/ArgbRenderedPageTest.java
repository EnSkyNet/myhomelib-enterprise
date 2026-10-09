package com.myhomelibcorp.reader.render.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgbRenderedPageTest {
    @Test
    void rejectsDimensionsWhosePixelCountOverflowsAnInt() {
        assertThatThrownBy(() -> ArgbRenderedPage.validate(0, Integer.MAX_VALUE, 2, new int[1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pixel buffer");
    }

    @Test
    void rejectsMismatchedPixelBuffer() {
        assertThatThrownBy(() -> ArgbRenderedPage.validate(0, 2, 2, new int[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pixel buffer");
    }
}
