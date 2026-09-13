package com.myhomelibcorp.domain.model.annotation;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Durable highlight/note entity independent of a concrete reader renderer. */
public record Annotation(
        String id,
        AnnotationType type,
        AnnotationAnchor anchor,
        String color,
        String note,
        Set<String> tags,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    public static final String DEFAULT_COLOR = "#FFF59D";

    public Annotation {
        id = required(id, "id");
        if (type == null) throw new IllegalArgumentException("type is required");
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        color = normalizeColor(color);
        note = note == null ? "" : note;
        tags = normalizeTags(tags);
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (updatedAt == null) updatedAt = createdAt;
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        if (type == AnnotationType.HIGHLIGHT && !anchor.hasRange()) {
            throw new IllegalArgumentException("highlight requires a non-empty text range");
        }
        if (type == AnnotationType.HIGHLIGHT && anchor.quote().isBlank()) {
            throw new IllegalArgumentException("highlight requires selected quote text");
        }
        if (type == AnnotationType.NOTE && note.isBlank()) {
            throw new IllegalArgumentException("note annotation requires note text");
        }
    }

    public Annotation withNote(String newNote, Instant when) {
        return new Annotation(id, type, anchor, color, newNote, tags, createdAt, requiredTime(when));
    }

    public Annotation withColor(String newColor, Instant when) {
        return new Annotation(id, type, anchor, newColor, note, tags, createdAt, requiredTime(when));
    }

    public Annotation withTags(Set<String> newTags, Instant when) {
        return new Annotation(id, type, anchor, color, note, newTags, createdAt, requiredTime(when));
    }

    public Annotation withAnchor(AnnotationAnchor newAnchor, Instant when) {
        return new Annotation(id, type, newAnchor, color, note, tags, createdAt, requiredTime(when));
    }

    private static Instant requiredTime(Instant value) {
        if (value == null) throw new IllegalArgumentException("timestamp is required");
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String normalizeColor(String value) {
        if (value == null || value.isBlank()) return DEFAULT_COLOR;
        String color = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!color.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
        return color;
    }

    private static Set<String> normalizeTags(Set<String> input) {
        if (input == null || input.isEmpty()) return Set.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : input) {
            if (value == null) continue;
            String tag = value.trim();
            if (!tag.isEmpty()) unique.putIfAbsent(tag.toLowerCase(java.util.Locale.ROOT), tag);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(unique.values()));
    }
}
