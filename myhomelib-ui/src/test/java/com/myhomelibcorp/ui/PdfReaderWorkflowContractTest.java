package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfReaderWorkflowContractTest {
    @Test
    void readerRoutesPdfThroughBackgroundPreparedOpenAndPersistsPagePosition() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        assertThat(controller).contains("PdfDocumentSession.open(source, pdfPassword)", "PdfPasswordRequiredException",
                "requestPdfPassword()", "PasswordField", "PdfReaderView", "pdfReaderView.openPrepared",
                "pdfReaderView.currentPosition()", "pdfReaderView.progressPercent()", "uiBackgroundExecutor");
        assertThat(controller).doesNotContain("PDDocument.load");
    }
    @Test
    void pdfTocSearchAndBookmarksStayBehindReaderAndApplicationBoundaries() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        String bookmarks = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/ReaderBookmarkCoordinator.java"));
        assertThat(controller).contains("pdfReaderView.setOnAddBookmark", "pdfReaderView.setOnBookmarks",
                "pdfReaderView.setOnToc", "pdfReaderView.setOnSearch", "pdfReaderView.searchTextAsync",
                "pdfReaderView.outlineEntries()", "bookmarkCoordinator.saveText", "pdfReaderView.goToPosition");
        assertThat(bookmarks).contains("persistenceService.saveBookmark");
        assertThat(controller).doesNotContain("org.apache.pdfbox", "PDDocument", "PDFTextStripper");
    }

}
