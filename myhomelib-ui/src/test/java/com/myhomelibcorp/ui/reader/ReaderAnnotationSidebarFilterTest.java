package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderAnnotationState;
import com.myhomelibcorp.reader.api.ReaderAnnotationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderAnnotationSidebarFilterTest {

    @Test
    void selectsByTypeTagAndTextAndSortsByDocumentOffset() {
        ReaderAnnotationOverlay later = overlay(
                "later", 50, ReaderAnnotationType.NOTE, "Need Review", "", Set.of("Work"), "Chapter B", Instant.parse("2026-01-02T00:00:00Z"));
        ReaderAnnotationOverlay earlier = overlay(
                "earlier", 10, ReaderAnnotationType.NOTE, "", "review quote", Set.of("Work"), "Chapter A", Instant.parse("2026-01-03T00:00:00Z"));
        ReaderAnnotationOverlay otherType = overlay(
                "highlight", 5, ReaderAnnotationType.HIGHLIGHT, "review", "", Set.of("Work"), "Chapter A", Instant.EPOCH);
        ReaderAnnotationPresentation presentation = new ReaderAnnotationPresentation(
                List.of(later, otherType, earlier), List.of());

        List<ReaderAnnotationOverlay> selected = ReaderAnnotationSidebarFilter.select(
                presentation, " REVIEW ", ReaderAnnotationType.NOTE, "Work");

        assertThat(selected).extracting(ReaderAnnotationOverlay::id).containsExactly("earlier", "later");
    }

    @Test
    void exposesStableCaseInsensitiveSortedTagInventoryWithoutBlankValues() {
        ReaderAnnotationPresentation presentation = new ReaderAnnotationPresentation(List.of(
                overlay("a", 1, ReaderAnnotationType.NOTE, "", "", Set.of("zeta", "Alpha", ""), "", Instant.EPOCH),
                overlay("b", 2, ReaderAnnotationType.NOTE, "", "", Set.of("beta", "Alpha"), "", Instant.EPOCH)
        ), List.of());

        assertThat(ReaderAnnotationSidebarFilter.tags(presentation))
                .containsExactly("Alpha", "beta", "zeta");
    }

    private static ReaderAnnotationOverlay overlay(String id, long offset, ReaderAnnotationType type,
                                                   String note, String quote, Set<String> tags,
                                                   String chapter, Instant updatedAt) {
        return new ReaderAnnotationOverlay(id, offset, offset + 1, "#FFF59D", type,
                note, quote, tags, chapter, ReaderAnnotationState.RESOLVED, updatedAt);
    }
}
