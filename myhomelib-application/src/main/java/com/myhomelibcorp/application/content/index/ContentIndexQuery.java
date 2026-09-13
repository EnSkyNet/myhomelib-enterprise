package com.myhomelibcorp.application.content.index;

/** Bounded query contract for the full-text content index. */
public record ContentIndexQuery(String collectionId, String text, String bookId, int offset, int limit) {
    public ContentIndexQuery {
        collectionId = required(collectionId, "collectionId");
        text = required(text, "text");
        bookId = normalize(bookId);
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(500, limit));
    }

    public static ContentIndexQuery allBooks(String collectionId, String text, int limit) {
        return new ContentIndexQuery(collectionId, text, null, 0, limit);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
