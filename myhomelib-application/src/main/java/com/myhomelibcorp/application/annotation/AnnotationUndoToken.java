package com.myhomelibcorp.application.annotation;

import java.time.Instant;
import java.util.Set;

/** Complete application snapshot required to undo one Annotation Manager deletion. */
public record AnnotationUndoToken(
        String id,
        AnnotationManagerType type,
        AnnotationAnchorData anchor,
        String color,
        String note,
        Set<String> tags,
        Instant createdAt,
        Instant updatedAt
) {
    public AnnotationUndoToken {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        id = id.trim();
        if (type == null) throw new IllegalArgumentException("type is required");
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        color = color == null ? "" : color;
        note = note == null ? "" : note;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) updatedAt = createdAt;
    }
}
