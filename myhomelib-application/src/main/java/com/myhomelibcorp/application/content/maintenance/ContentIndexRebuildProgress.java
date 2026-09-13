package com.myhomelibcorp.application.content.maintenance;

public record ContentIndexRebuildProgress(long processedBooks, long totalBooks, String currentBook, String phase) {
    public ContentIndexRebuildProgress {
        processedBooks = Math.max(0L, processedBooks);
        totalBooks = Math.max(0L, totalBooks);
        currentBook = currentBook == null ? "" : currentBook;
        phase = phase == null ? "" : phase;
    }
}
