package com.myhomelibcorp.application.webreader;

/** One chapter prepared for the browser reader. */
public record WebReaderChapter(
        int index,
        String id,
        String title,
        long startOffset,
        long endOffset,
        String text) {
    public WebReaderChapter {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        if (startOffset < 0 || endOffset < startOffset) throw new IllegalArgumentException("invalid offsets");
    }
}
