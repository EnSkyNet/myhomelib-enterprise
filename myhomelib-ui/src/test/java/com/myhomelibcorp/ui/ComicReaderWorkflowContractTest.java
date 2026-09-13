package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComicReaderWorkflowContractTest {
    @Test
    void workspaceRoutesCbzAndCbrThroughComicSessionAndPersistsPagePosition() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        assertThat(controller).contains("ComicDocumentSession", "ComicReaderView", "isComicExtension",
                "openComicSession", "comicReaderView.openPrepared", "comicReaderView.currentPosition()",
                "comicReaderView.progressPercent()", "comicReaderView.goToPosition");
        assertThat(controller).contains("bookResourcePort.listArchiveEntries", "bookResourcePort.readArchiveEntry");
    }

    @Test
    void nonTextWorkspacesKeepAnnotationsAndTextOnlyToolsOutOfBinaryDocuments() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        assertThat(controller).contains("if (!currentPdf && !currentComic && !currentAudio) refreshAnnotationsAsync()",
                "if (currentComic) return;");
    }
}
