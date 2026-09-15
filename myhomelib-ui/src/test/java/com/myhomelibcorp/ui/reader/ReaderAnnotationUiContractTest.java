package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderAnnotationUiContractTest {
    @Test
    void readerWorkspaceUsesFullAnnotationProjectionAndCompleteInteractiveWorkflow() throws Exception {
        String workspace = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        String coordinator = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationCoordinator.java"));
        String presenter = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationPresenter.java"));
        String popover = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationPopover.java"));

        assertThat(workspace).contains(
                "AnnotationService annotationService",
                "ReaderAnnotationCoordinator",
                "setOnHighlightRequested(this::createHighlightFromSelection)",
                "setOnNoteRequested(this::createNoteFromSelection)",
                "setOnAnnotationActivated(this::showAnnotationPopover)",
                "refreshAnnotationsAsync()",
                "listBookAnnotationViews(bookId)",
                "ReaderAnnotationPresenter.presentation",
                "reviewAnnotationIssues()");
        assertThat(coordinator).contains(
                "AnnotationEditorDialog",
                "ReaderAnnotationPopover",
                "annotationService.update(",
                "annotationService.delete(",
                "annotationService.reanchor(");
        assertThat(popover).contains("copyQuote", "copyQuoteAndNote");
        assertThat(workspace).doesNotContain("SqliteAnnotationRepository");
        assertThat(coordinator).doesNotContain("SqliteAnnotationRepository");
        assertThat(presenter).doesNotContain("com.myhomelibcorp.domain.model.annotation");
        assertThat(presenter).contains("AnnotationAnchorData", "AnnotationReaderItem", "AnnotationReaderResolver",
                "findRebindCandidate", "ReaderAnnotationState.RELOCATED", "ARTIFACT_MISMATCH", "UNRESOLVED");
    }

    @Test
    void readerModuleSupportsHitTestingKeyboardCyclingAndBookMap() throws Exception {
        Path readerRoot = Path.of("../myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx");
        String canvas = Files.readString(readerRoot.resolve("ReaderCanvas.java"));
        String hitTest = Files.readString(readerRoot.resolve("ReaderAnnotationHitTest.java"));
        String keyboard = Files.readString(readerRoot.resolve("ReaderKeyboardScrollController.java"));
        String toolbar = Files.readString(readerRoot.resolve("ReaderToolbar.java"));

        assertThat(canvas).contains("setAnnotationOverlays", "renderAnnotationOverlays",
                "ReaderAnnotationHitTest.hit", "setOnAnnotationActivated", "Cursor.HAND");
        assertThat(hitTest).contains("marker", "rangeLength", "updatedAt", "visibleOrdered");
        assertThat(keyboard).contains("KeyCode.N", "event.isAltDown()", "activateNextAnnotationFromInput");
        assertThat(toolbar).contains("bookMapButton", "ui.reader.toolbar.bookmap", "onBookMapClick",
                "moreButton", "ui.reader.toolbar.more", "proxyItem");
    }

    @Test
    void workspaceFxmlContainsUnifiedReaderSidebarForTocSearchBookmarksAnnotationsAndBookMap() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/new-reader-workspace.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("readerSidebarTabs", "tocSidebarTab", "searchSidebarTab", "bookmarkSidebarTab",
                "annotationSidebarTab", "bookMapSidebarTab", "annotationSidebarUnavailable");
    }
}
