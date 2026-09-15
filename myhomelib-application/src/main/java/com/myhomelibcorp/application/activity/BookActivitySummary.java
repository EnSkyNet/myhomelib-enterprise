package com.myhomelibcorp.application.activity;

/** Lightweight per-book user-data counters used by catalog badges and search filters. */
public record BookActivitySummary(String bookId, int noteCount, int highlightCount, int bookmarkCount) {
    public BookActivitySummary {
        bookId = bookId == null ? "" : bookId.trim();
        noteCount = Math.max(0, noteCount);
        highlightCount = Math.max(0, highlightCount);
        bookmarkCount = Math.max(0, bookmarkCount);
    }

    public static BookActivitySummary empty(String bookId) {
        return new BookActivitySummary(bookId, 0, 0, 0);
    }

    public int annotationCount() {
        return noteCount + highlightCount;
    }
}
