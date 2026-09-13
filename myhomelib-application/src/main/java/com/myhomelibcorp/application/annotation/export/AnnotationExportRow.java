package com.myhomelibcorp.application.annotation.export;

import java.time.Instant;
import java.util.List;

/** Immutable flattened projection used only by bounded annotation export. */
public record AnnotationExportRow(
        String id,
        String bookId,
        String bookTitle,
        String authors,
        String series,
        String language,
        String fileName,
        String isbn,
        String type,
        String color,
        String chapterId,
        String chapterTitle,
        String quote,
        String note,
        List<String> tags,
        double position,
        Instant createdAt,
        Instant updatedAt
) {
    public AnnotationExportRow {
        id = required(id, "id");
        bookId = required(bookId, "bookId");
        bookTitle = safe(bookTitle);
        authors = safe(authors);
        series = safe(series);
        language = safe(language);
        fileName = safe(fileName);
        isbn = safe(isbn);
        type = safe(type);
        color = safe(color);
        chapterId = safe(chapterId);
        chapterTitle = safe(chapterTitle);
        quote = safe(quote);
        note = safe(note);
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (!Double.isFinite(position)) position = 0.0;
        position = Math.max(0.0, Math.min(1.0, position));
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) updatedAt = createdAt;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
