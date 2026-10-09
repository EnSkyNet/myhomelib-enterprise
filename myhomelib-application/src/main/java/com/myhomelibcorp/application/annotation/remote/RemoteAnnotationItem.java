package com.myhomelibcorp.application.annotation.remote;

import java.time.Instant;
import java.util.List;

public record RemoteAnnotationItem(
        String annotationId, String bookId, String bookTitle, String authors,
        String quote, String note, List<String> tags, double position,
        Instant highlightedAt, String fingerprint) {
    public RemoteAnnotationItem {
        annotationId = required(annotationId, "annotationId");
        bookId = required(bookId, "bookId");
        bookTitle = safe(bookTitle); authors = safe(authors); quote = safe(quote); note = safe(note);
        tags = tags == null ? List.of() : List.copyOf(tags);
        position = Math.max(0.0, Math.min(1.0, Double.isFinite(position) ? position : 0.0));
        if (highlightedAt == null) throw new IllegalArgumentException("highlightedAt is required");
        fingerprint = required(fingerprint, "fingerprint");
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
