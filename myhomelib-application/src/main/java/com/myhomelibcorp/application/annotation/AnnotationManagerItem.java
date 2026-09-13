package com.myhomelibcorp.application.annotation;

import java.time.Instant;
import java.util.List;

/** Read-only row projection shown by Annotation Manager. */
public record AnnotationManagerItem(
        String id,
        String bookId,
        String bookTitle,
        AnnotationManagerType type,
        String color,
        String note,
        List<String> tags,
        String chapterTitle,
        String quote,
        double position,
        Instant createdAt,
        Instant updatedAt
) {
    public AnnotationManagerItem {
        id = required(id, "id");
        bookId = required(bookId, "bookId");
        bookTitle = safe(bookTitle);
        if (type == null) throw new IllegalArgumentException("type is required");
        color = safe(color);
        note = safe(note);
        tags = tags == null ? List.of() : List.copyOf(tags);
        chapterTitle = safe(chapterTitle);
        quote = safe(quote);
        if (!Double.isFinite(position)) position = 0.0;
        position = Math.max(0.0, Math.min(1.0, position));
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) updatedAt = createdAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
