package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;

/** Application projection required to render one persisted annotation in a reader adapter. */
public record AnnotationReaderItem(
        String id,
        AnnotationAnchorData anchor,
        String color,
        boolean note
) {
    public AnnotationReaderItem {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        id = id.trim();
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        color = color == null || color.isBlank() ? Annotation.DEFAULT_COLOR : color.trim().toUpperCase(java.util.Locale.ROOT);
        if (!color.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
    }
    static AnnotationReaderItem fromDomain(Annotation annotation) {
        return new AnnotationReaderItem(
                annotation.id(),
                AnnotationAnchorData.fromDomain(annotation.anchor()),
                annotation.color(),
                annotation.type() == AnnotationType.NOTE);
    }
}
