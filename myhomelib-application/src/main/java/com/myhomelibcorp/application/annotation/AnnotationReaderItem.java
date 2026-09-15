package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.Annotation;

import java.time.Instant;
import java.util.Set;

/** Application projection required to render and interact with one persisted annotation. */
public record AnnotationReaderItem(
        String id,
        AnnotationAnchorData anchor,
        String color,
        AnnotationReaderType type,
        String noteText,
        Set<String> tags,
        Instant updatedAt
) {
    /** Backward-compatible constructor for existing renderer tests/callers. */
    public AnnotationReaderItem(String id, AnnotationAnchorData anchor, String color, boolean note) {
        this(id, anchor, color, note ? AnnotationReaderType.NOTE : AnnotationReaderType.HIGHLIGHT,
                "", Set.of(), Instant.EPOCH);
    }

    /** Convenience constructor for callers that do not care about the timestamp. */
    public AnnotationReaderItem(
            String id,
            AnnotationAnchorData anchor,
            String color,
            AnnotationReaderType type,
            String noteText,
            Set<String> tags
    ) {
        this(id, anchor, color, type, noteText, tags, Instant.EPOCH);
    }

    public AnnotationReaderItem {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        id = id.trim();
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        color = color == null || color.isBlank()
                ? Annotation.DEFAULT_COLOR
                : color.trim().toUpperCase(java.util.Locale.ROOT);
        if (!color.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
        if (type == null) type = AnnotationReaderType.HIGHLIGHT;
        noteText = noteText == null ? "" : noteText;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    /** Compatibility helper for older UI contracts. */
    public boolean note() {
        return type == AnnotationReaderType.NOTE;
    }

    static AnnotationReaderItem fromDomain(Annotation annotation) {
        return new AnnotationReaderItem(
                annotation.id(),
                AnnotationAnchorData.fromDomain(annotation.anchor()),
                annotation.color(),
                AnnotationReaderType.fromDomain(annotation.type()),
                annotation.note(),
                annotation.tags(),
                annotation.updatedAt());
    }
}
