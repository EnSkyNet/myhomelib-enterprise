package com.myhomelibcorp.application.content;

/** Searchable text anchor with absolute document offsets. */
public record ContentAnchor(
        String id,
        String chapterId,
        String paragraphId,
        long startOffset,
        long endOffset
) {
    public ContentAnchor {
        id = required(id, "id");
        chapterId = required(chapterId, "chapterId");
        paragraphId = required(paragraphId, "paragraphId");
        if (startOffset < 0L) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset < startOffset) throw new IllegalArgumentException("endOffset must be >= startOffset");
    }

    public long length() {
        return endOffset - startOffset;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
