package com.myhomelibcorp.application.annotation;

import java.time.LocalDate;

/** Immutable filter contract for the Annotation Manager query adapter. */
public record AnnotationManagerFilter(
        String searchText,
        String bookId,
        AnnotationManagerType type,
        String color,
        String tag,
        LocalDate dateFrom,
        LocalDate dateTo
) {
    public AnnotationManagerFilter {
        searchText = normalize(searchText);
        bookId = normalize(bookId);
        color = normalize(color);
        if (color != null) color = color.toUpperCase(java.util.Locale.ROOT);
        tag = normalize(tag);
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom cannot be after dateTo");
        }
    }

    public static AnnotationManagerFilter empty() {
        return new AnnotationManagerFilter(null, null, null, null, null, null, null);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
