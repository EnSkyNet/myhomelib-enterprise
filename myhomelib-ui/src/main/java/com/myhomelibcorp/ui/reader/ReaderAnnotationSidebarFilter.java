package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderAnnotationType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Pure selection model for the Reader annotation sidebar. */
final class ReaderAnnotationSidebarFilter {
    private ReaderAnnotationSidebarFilter() { }

    static List<String> tags(ReaderAnnotationPresentation presentation) {
        if (presentation == null) return List.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        presentation.overlays().stream()
                .flatMap(item -> item.tags().stream())
                .filter(tag -> tag != null && !tag.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(tags::add);
        return List.copyOf(tags);
    }

    static List<ReaderAnnotationOverlay> select(
            ReaderAnnotationPresentation presentation,
            String query,
            ReaderAnnotationType type,
            String tag
    ) {
        if (presentation == null) return List.of();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return presentation.overlays().stream()
                .filter(item -> type == null || item.type() == type)
                .filter(item -> tag == null || item.tags().contains(tag))
                .filter(item -> normalizedQuery.isEmpty() || matches(item, normalizedQuery))
                .sorted(java.util.Comparator.comparingLong(ReaderAnnotationOverlay::startOffset)
                        .thenComparing(ReaderAnnotationOverlay::updatedAt))
                .toList();
    }

    private static boolean matches(ReaderAnnotationOverlay item, String query) {
        if (item.noteText().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (item.quote().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (item.chapterTitle().toLowerCase(Locale.ROOT).contains(query)) return true;
        return item.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(query));
    }
}
