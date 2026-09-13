package com.myhomelibcorp.application.annotation;

import java.util.List;

/** Bounded page of Annotation Manager rows. */
public record AnnotationManagerPage(List<AnnotationManagerItem> items, long total, int offset, int limit) {
    public AnnotationManagerPage {
        items = items == null ? List.of() : List.copyOf(items);
        total = Math.max(0L, total);
        offset = Math.max(0, offset);
        limit = Math.max(1, limit);
    }

    public boolean hasPrevious() { return offset > 0; }
    public boolean hasNext() { return (long) offset + items.size() < total; }
}
