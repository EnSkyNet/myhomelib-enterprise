package com.myhomelibcorp.reader.api;

public record SearchMatch(
        int chapterIndex,
        long textOffset,
        int paragraphIndex,
        int startChar,
        int length,
        String context
) {
    public ReaderPosition toPosition() {
        return new ReaderPosition(chapterIndex, textOffset, paragraphIndex, startChar);
    }

    public String getMatchText(String fullText) {
        if (fullText == null || textOffset >= fullText.length()) {
            return "";
        }
        int start = (int) textOffset + startChar;
        int end = Math.min(start + length, fullText.length());
        return fullText.substring(start, end);
    }

    public boolean isEmpty() {
        return length == 0;
    }
}