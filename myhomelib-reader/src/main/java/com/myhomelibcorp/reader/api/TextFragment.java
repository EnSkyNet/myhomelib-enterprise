package com.myhomelibcorp.reader.api;

import java.util.List;

public record TextFragment(
        String text,
        List<StyleSpan> spans
) {
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }

    public int length() {
        return text != null ? text.length() : 0;
    }
}