package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderAnnotationState;
import com.myhomelibcorp.reader.api.ReaderAnnotationType;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderAnnotationHitTestTest {

    @Test
    void noteMarkerWinsWhenRangesOverlap() {
        PageLayout page = page();
        ReaderAnnotationOverlay highlight = overlay("h", 2, 8, ReaderAnnotationType.HIGHLIGHT,
                Instant.parse("2026-09-14T10:00:00Z"));
        ReaderAnnotationOverlay note = overlay("n", 2, 8, ReaderAnnotationType.NOTE,
                Instant.parse("2026-09-14T09:00:00Z"));

        // line starts at x=10, width=100, 10 chars; offset 2 marker is around x=30, y=22
        var hit = ReaderAnnotationHitTest.hit(List.of(highlight, note), page, null, 0, false, 30, 22);

        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().id()).isEqualTo("n");
    }

    @Test
    void smallestRangeWinsThenNewestWhenClickIsInsideOverlappingRanges() {
        PageLayout page = page();
        ReaderAnnotationOverlay wide = overlay("wide", 1, 9, ReaderAnnotationType.HIGHLIGHT,
                Instant.parse("2026-09-14T12:00:00Z"));
        ReaderAnnotationOverlay narrowOld = overlay("narrow-old", 3, 7, ReaderAnnotationType.HIGHLIGHT,
                Instant.parse("2026-09-14T11:00:00Z"));
        ReaderAnnotationOverlay narrowNew = overlay("narrow-new", 3, 7, ReaderAnnotationType.HIGHLIGHT,
                Instant.parse("2026-09-14T13:00:00Z"));

        var hit = ReaderAnnotationHitTest.hit(
                List.of(wide, narrowOld, narrowNew), page, null, 0, false, 60, 30);

        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().id()).isEqualTo("narrow-new");
    }

    @Test
    void visibleAnnotationsAreReturnedInDocumentOrder() {
        PageLayout page = page();
        ReaderAnnotationOverlay later = overlay("later", 7, 9, ReaderAnnotationType.HIGHLIGHT, Instant.EPOCH);
        ReaderAnnotationOverlay earlier = overlay("earlier", 1, 3, ReaderAnnotationType.HIGHLIGHT, Instant.EPOCH);

        assertThat(ReaderAnnotationHitTest.visibleOrdered(List.of(later, earlier), page, null, false))
                .extracting(ReaderAnnotationOverlay::id)
                .containsExactly("earlier", "later");
    }

    private static PageLayout page() {
        LineLayout line = new LineLayout("abcdefghij", 10, 20, 100, 20, 14,
                0, 0, TextStyle.NORMAL, 0, 10);
        return PageLayout.builder()
                .startOffset(0)
                .endOffset(10)
                .lines(List.of(line))
                .width(120)
                .height(80)
                .build();
    }

    private static ReaderAnnotationOverlay overlay(
            String id,
            long start,
            long end,
            ReaderAnnotationType type,
            Instant updatedAt
    ) {
        return new ReaderAnnotationOverlay(id, start, end, "#FFF59D", type,
                type == ReaderAnnotationType.NOTE ? "memo" : "", "quote", Set.of(),
                ReaderAnnotationState.RESOLVED, updatedAt);
    }
}
