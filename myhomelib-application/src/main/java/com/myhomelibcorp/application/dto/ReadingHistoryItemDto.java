package com.myhomelibcorp.application.dto;

import java.time.LocalDateTime;

/** One recent/history item with the timestamp needed by the desktop Recent menu. */
public record ReadingHistoryItemDto(BookDto book, LocalDateTime lastOpenedAt) {
    public ReadingHistoryItemDto {
        if (book == null) throw new IllegalArgumentException("book cannot be null");
        if (lastOpenedAt == null) throw new IllegalArgumentException("lastOpenedAt cannot be null");
    }
}
