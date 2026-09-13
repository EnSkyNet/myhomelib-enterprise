package com.myhomelibcorp.application.annotation.export;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Selects either the whole library or one/more logical books for annotation export. */
public record AnnotationExportSelection(boolean allBooks, Set<String> bookIds) {
    public AnnotationExportSelection {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (bookIds != null) {
            for (String id : bookIds) {
                if (id != null && !id.isBlank()) normalized.add(id.trim());
            }
        }
        bookIds = Set.copyOf(normalized);
        if (!allBooks && bookIds.isEmpty()) {
            throw new IllegalArgumentException("At least one book is required unless allBooks=true");
        }
        if (allBooks) bookIds = Set.of();
    }

    public static AnnotationExportSelection all() {
        return new AnnotationExportSelection(true, Set.of());
    }

    public static AnnotationExportSelection books(Collection<String> bookIds) {
        return new AnnotationExportSelection(false, bookIds == null ? Set.of() : new LinkedHashSet<>(bookIds));
    }
}
