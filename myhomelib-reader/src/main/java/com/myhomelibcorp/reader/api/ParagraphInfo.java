package com.myhomelibcorp.reader.api;

public record ParagraphInfo(
        int offset,
        int index,
        TextStyle style
) {
    public static ParagraphInfo of(int offset, int index) {
        return new ParagraphInfo(offset, index, TextStyle.NORMAL);
    }

    public static ParagraphInfo of(int offset, int index, TextStyle style) {
        return new ParagraphInfo(offset, index, style);
    }
}