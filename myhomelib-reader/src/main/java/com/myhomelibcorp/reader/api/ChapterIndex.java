package com.myhomelibcorp.reader.api;

public record ChapterIndex(
        String id,
        String title,
        long startOffset,
        long endOffset,
        int paragraphCount
) {
    public boolean isEmpty() {
        return startOffset == endOffset;
    }

    public long length() {
        return endOffset - startOffset;
    }

    public boolean containsOffset(long offset) {
        return offset >= startOffset && offset < endOffset;
    }
}