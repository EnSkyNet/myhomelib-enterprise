package com.myhomelibcorp.application.content.index;

import com.myhomelibcorp.application.content.ExtractedContent;

/** One extracted artifact ready to be written into the independent content index. */
public record ContentIndexEntry(String bookId, String artifactId, ExtractedContent content) {
    public ContentIndexEntry {
        bookId = required(bookId, "bookId");
        artifactId = required(artifactId, "artifactId");
        if (content == null) throw new IllegalArgumentException("content is required");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
