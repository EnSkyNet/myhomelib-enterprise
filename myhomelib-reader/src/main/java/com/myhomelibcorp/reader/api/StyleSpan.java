package com.myhomelibcorp.reader.api;

public record StyleSpan(
        int start,
        int end,
        TextStyle style
) {
    public int length() {
        return end - start;
    }

    public boolean contains(int position) {
        return position >= start && position < end;
    }
}