package com.myhomelibcorp.application.dto;

import java.time.LocalDateTime;

/** One item in the cross-device Continue Reading shelf. */
public record ContinueReadingItemDto(
        String bookId,
        String title,
        String authors,
        double percent,
        String chapterTitle,
        LocalDateTime updatedAt,
        String lastDevice
) {
    public ContinueReadingItemDto {
        bookId = bookId == null ? "" : bookId;
        title = title == null ? "" : title;
        authors = authors == null ? "" : authors;
        chapterTitle = chapterTitle == null ? "" : chapterTitle;
        lastDevice = lastDevice == null || lastDevice.isBlank() ? "desktop" : lastDevice;
        percent = Math.max(0.0, Math.min(100.0, percent));
    }
}
