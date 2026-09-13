package com.myhomelibcorp.reader.api;

import java.util.Objects;

/**
 * Lightweight renderer value for a persisted annotation that has already been resolved to the
 * currently opened document text. Persistence/domain concerns intentionally stay outside Reader.
 */
public record ReaderAnnotationOverlay(
        String id,
        long startOffset,
        long endOffset,
        String color,
        boolean note
) {
    public ReaderAnnotationOverlay {
        id = Objects.requireNonNullElse(id, "").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id is required");
        if (startOffset < 0) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset < startOffset) throw new IllegalArgumentException("endOffset must be >= startOffset");
        color = normalizeColor(color);
    }

    public boolean hasRange() {
        return endOffset > startOffset;
    }

    private static String normalizeColor(String value) {
        String normalized = value == null || value.isBlank() ? "#FFF59D" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
        return normalized;
    }
}
