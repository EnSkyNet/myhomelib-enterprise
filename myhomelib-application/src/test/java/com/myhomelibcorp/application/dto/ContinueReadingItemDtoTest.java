package com.myhomelibcorp.application.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ContinueReadingItemDtoTest {
    @Test
    void nonFinitePercentDoesNotLeakIntoDashboard() {
        ContinueReadingItemDto item = new ContinueReadingItemDto(
                "b", "Book", "", Double.NaN, "", LocalDateTime.now(), "desktop");
        assertThat(item.percent()).isZero();
    }
}
