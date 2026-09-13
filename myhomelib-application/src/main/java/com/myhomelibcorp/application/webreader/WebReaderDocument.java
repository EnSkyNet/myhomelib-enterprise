package com.myhomelibcorp.application.webreader;

import java.util.List;

/** Format-neutral snapshot used by the server-rendered browser reader. */
public record WebReaderDocument(
        String bookId,
        String title,
        String format,
        boolean supported,
        String message,
        List<WebReaderChapter> chapters,
        int selectedChapter,
        int resumeChapter,
        long resumeOffset,
        double resumePercent) {
    public WebReaderDocument {
        bookId = bookId == null ? "" : bookId;
        title = title == null ? "" : title;
        format = format == null ? "" : format;
        message = message == null ? "" : message;
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        if (selectedChapter < 0) selectedChapter = 0;
        if (resumeChapter < 0) resumeChapter = 0;
        resumePercent = Math.max(0.0, Math.min(100.0, resumePercent));
    }

    public WebReaderChapter currentChapter() {
        if (chapters.isEmpty()) return new WebReaderChapter(0, "", "", 0, 0, "");
        return chapters.get(Math.min(selectedChapter, chapters.size() - 1));
    }
}
