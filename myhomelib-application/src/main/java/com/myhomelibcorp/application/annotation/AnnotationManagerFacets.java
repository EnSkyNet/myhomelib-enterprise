package com.myhomelibcorp.application.annotation;

import java.util.List;

/** Small distinct-value sets used to populate Annotation Manager filters. */
public record AnnotationManagerFacets(List<BookFacet> books, List<String> colors, List<String> tags) {
    public AnnotationManagerFacets {
        books = books == null ? List.of() : List.copyOf(books);
        colors = colors == null ? List.of() : List.copyOf(colors);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public record BookFacet(String id, String title) {
        public BookFacet {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("book id is required");
            id = id.trim();
            title = title == null ? "" : title;
        }
    }
}
