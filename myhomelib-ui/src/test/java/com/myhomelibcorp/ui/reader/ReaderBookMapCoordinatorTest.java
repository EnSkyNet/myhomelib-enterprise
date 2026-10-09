package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderBookMapCoordinatorTest {

    @Test
    void buildsChapterStateAndAggregatesAnnotationsAndBookmarksWithoutChangingBoundarySemantics() {
        LocalizationService i18n = mock(LocalizationService.class);
        ReaderBookMapCoordinator coordinator = new ReaderBookMapCoordinator(i18n);
        ReaderDocument document = mock(ReaderDocument.class);
        List<ChapterIndex> chapters = List.of(
                new ChapterIndex("c1", "Intro", 0, 100, 1),
                new ChapterIndex("c2", "", 100, 200, 1));
        when(document.chapters()).thenReturn(chapters);
        when(document.totalTextLength()).thenReturn(200L);
        when(i18n.text("ui.reader.bookmap.untitled_chapter")).thenReturn("Без назви");

        ReaderPosition first = new ReaderPosition(0, 30, 0, 0);
        ReaderPosition second = new ReaderPosition(1, 170, 0, 0);

        ReaderAnnotationPresentation annotations = new ReaderAnnotationPresentation(List.of(
                new ReaderAnnotationOverlay("n1", 10, 20, "#FFF59D", true),
                new ReaderAnnotationOverlay("h1", 25, 30, "#FFF59D", false),
                new ReaderAnnotationOverlay("h2", 150, 160, "#FFF59D", false)), List.of());

        List<ReaderBookMapCoordinator.BookMapRow> rows = coordinator.buildRows(
                document, new ReaderPosition(1, 120, 0, 0), annotations, List.of(first, second));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).chapterIndex()).isZero();
        assertThat(rows.get(0).startOffset()).isZero();
        assertThat(rows.get(0).label()).isEqualTo("✓ Intro   📝 1   ▰ 1   🔖 1");
        assertThat(rows.get(1).chapterIndex()).isEqualTo(1);
        assertThat(rows.get(1).startOffset()).isEqualTo(100L);
        assertThat(rows.get(1).label()).isEqualTo("▶ Без назви   📝 0   ▰ 1   🔖 1");
    }

    @Test
    void preservesPassedStateAtChapterEnd() {
        LocalizationService i18n = mock(LocalizationService.class);
        ReaderBookMapCoordinator coordinator = new ReaderBookMapCoordinator(i18n);
        ReaderDocument document = mock(ReaderDocument.class);
        when(document.chapters()).thenReturn(List.of(new ChapterIndex("c", "Only", 0, 10, 1)));
        when(document.totalTextLength()).thenReturn(10L);

        List<ReaderBookMapCoordinator.BookMapRow> rows = coordinator.buildRows(
                document, new ReaderPosition(0, 10, 0, 0),
                new ReaderAnnotationPresentation(List.of(), List.of()), List.of());

        assertThat(rows).extracting(ReaderBookMapCoordinator.BookMapRow::label)
                .containsExactly("✓ Only   📝 0   ▰ 0   🔖 0");
    }
}
