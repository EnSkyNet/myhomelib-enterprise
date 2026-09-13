package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderAnnotationUiContractTest {
    @Test
    void readerWorkspaceUsesApplicationServiceAndAsyncRefreshForAnnotations() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));

        assertThat(source).contains(
                "AnnotationService annotationService",
                "setOnHighlightRequested(this::createHighlightFromSelection)",
                "setOnNoteRequested(this::createNoteFromSelection)",
                "uiBackgroundExecutor.submit(() -> annotationService.createHighlight",
                "uiBackgroundExecutor.submit(() -> annotationService.createNote",
                "refreshAnnotationsAsync()",
                "listBookAnnotationViews(bookId)",
                "ReaderAnnotationPresenter.overlays");
        assertThat(source).doesNotContain("SqliteAnnotationRepository");
        String presenter = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/ReaderAnnotationPresenter.java"));
        assertThat(presenter).doesNotContain("com.myhomelibcorp.domain.model.annotation");
        assertThat(presenter).contains("AnnotationAnchorData", "AnnotationReaderItem", "AnnotationReaderResolver");
    }

    @Test
    void readerModuleExposesHandlesKeyboardSelectionContextActionsAndPersistentOverlays() throws Exception {
        Path readerRoot = Path.of("../myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx");
        String canvas = Files.readString(readerRoot.resolve("ReaderCanvas.java"));
        String selection = Files.readString(readerRoot.resolve("ReaderSelectionController.java"));
        String keyboard = Files.readString(readerRoot.resolve("ReaderKeyboardScrollController.java"));

        assertThat(selection).contains("beginHandleDrag", "extendByCharacters", "ReaderSelection", "renderHandle");
        assertThat(canvas).contains("ui.reader.selection.highlight", "ui.reader.selection.note",
                "setAnnotationOverlays", "renderAnnotationOverlays");
        assertThat(keyboard).contains("event.isShiftDown() && code == KeyCode.LEFT",
                "event.isShiftDown() && code == KeyCode.RIGHT",
                "requestHighlightFromInput", "requestNoteFromInput");
    }
}
