package com.myhomelibcorp.application.annotation.export;

import java.util.List;

public record AnnotationExportPage(List<AnnotationExportRow> items, int offset, int limit, boolean hasNext) {
    public AnnotationExportPage {
        items = items == null ? List.of() : List.copyOf(items);
        offset = Math.max(0, offset);
        limit = Math.max(1, limit);
    }
}
