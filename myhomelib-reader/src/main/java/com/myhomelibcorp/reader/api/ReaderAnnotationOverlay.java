package com.myhomelibcorp.reader.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight renderer value for a persisted annotation that has already been resolved to the
 * immutable reader document. It contains enough read-only data for Reader interaction without
 * introducing a dependency on annotation persistence/domain types.
 */
public record ReaderAnnotationOverlay(
        String id,
        long startOffset,
        long endOffset,
        String color,
        ReaderAnnotationType type,
        String noteText,
        String quote,
        Set<String> tags,
        String chapterTitle,
        ReaderAnnotationState state,
        Instant updatedAt
) {
    /** Compatibility constructor used by existing render-only callers/tests. */
    public ReaderAnnotationOverlay(String id, long startOffset, long endOffset, String color, boolean note) {
        this(id, startOffset, endOffset, color,
                note ? ReaderAnnotationType.NOTE : ReaderAnnotationType.HIGHLIGHT,
                "", "", Set.of(), "", ReaderAnnotationState.RESOLVED, Instant.EPOCH);
    }

    /** Compatibility constructor retained for the Iteration 83 projection shape. */
    public ReaderAnnotationOverlay(
            String id,
            long startOffset,
            long endOffset,
            String color,
            boolean note,
            String noteText,
            String quote,
            Set<String> tags,
            boolean relocated
    ) {
        this(id, startOffset, endOffset, color,
                note ? ReaderAnnotationType.NOTE : ReaderAnnotationType.HIGHLIGHT,
                noteText, quote, tags, "",
                relocated ? ReaderAnnotationState.RELOCATED : ReaderAnnotationState.RESOLVED,
                Instant.EPOCH);
    }

    /** Compatibility constructor for callers that already use the explicit type/state payload. */
    public ReaderAnnotationOverlay(
            String id,
            long startOffset,
            long endOffset,
            String color,
            ReaderAnnotationType type,
            String noteText,
            String quote,
            Set<String> tags,
            ReaderAnnotationState state,
            Instant updatedAt
    ) {
        this(id, startOffset, endOffset, color, type, noteText, quote, tags, "", state, updatedAt);
    }

    public ReaderAnnotationOverlay {
        id = Objects.requireNonNullElse(id, "").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id is required");
        if (startOffset < 0) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset < startOffset) throw new IllegalArgumentException("endOffset must be >= startOffset");
        color = normalizeColor(color);
        type = type == null ? ReaderAnnotationType.HIGHLIGHT : type;
        noteText = Objects.requireNonNullElse(noteText, "");
        quote = Objects.requireNonNullElse(quote, "");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        chapterTitle = Objects.requireNonNullElse(chapterTitle, "").trim();
        state = state == null ? ReaderAnnotationState.RESOLVED : state;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public boolean hasRange() {
        return endOffset > startOffset;
    }

    public boolean note() {
        return type == ReaderAnnotationType.NOTE;
    }

    public boolean relocated() {
        return state == ReaderAnnotationState.RELOCATED;
    }

    /** Text shown in Reader details; notes prefer the user's text, highlights use the quote. */
    public String displayText() {
        return note() && !noteText.isBlank() ? noteText : quote;
    }

    public long rangeLength() {
        return Math.max(0L, endOffset - startOffset);
    }

    private static String normalizeColor(String value) {
        String normalized = value == null || value.isBlank()
                ? "#FFF59D"
                : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
        return normalized;
    }
}
