package com.myhomelibcorp.ui.annotation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationManagerUiContractTest {
    @Test
    void workspaceExposesFilterJumpEditDeleteUndoExportAndPagedAsyncQuery() throws Exception {
        String fxml;
        try (var in = getClass().getResourceAsStream("/view/annotation-manager-workspace.fxml")) {
            assertThat(in).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("searchField", "bookFilter", "typeFilter", "colorFilter", "tagFilter",
                "dateFromFilter", "dateToFilter", "#openSelected", "#editSelected", "#deleteSelected",
                "#undoDelete", "#exportFilteredCsv", "#exportRich", "#exportKnowledgeMarkdown", "#previousPage", "#nextPage");
        assertThat(fxml).doesNotContain("columnResizePolicy=\"CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN\"");

        String controller = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/annotation/AnnotationManagerWorkspaceController.java"));
        assertThat(controller).contains("TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN",
                "AnnotationManagerService annotationManagerService",
                "executor.submit", "annotationManagerService.query", "annotationManagerService.deleteForUndo",
                "annotationManagerService.restoreDeleted", "workspaceManager.showAnnotationInReader",
                "EXPORT_PAGE_SIZE", "StandardCharsets.UTF_8", "AnnotationExportService annotationExportService",
                "annotationExportService.export(request)", "KnowledgeMarkdownExportService knowledgeMarkdownExportService",
                "knowledgeMarkdownExportService.export(request)");
        assertThat(controller).doesNotContain("SqliteAnnotation", "domain.model.annotation", "AnnotationRepository");
    }

    @Test
    void readerAcceptsOneShotAnnotationTargetWithoutAddingReaderPersistenceDependency() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
        String manager = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java"));
        assertThat(controller).contains("setAnnotationTargetId", "jumpToAnnotationTarget",
                "readerView.goToPosition(position)", "annotationTargetId = null");
        assertThat(manager).contains("showAnnotationInReader", "loadNewReaderWorkspace(bookId, annotationId)");
    }
    @Test
    void mainMenuRoutesAnnotationsThroughReaderSafeNavigationCoordinator() throws Exception {
        String main = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/controller/MainController.java"));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/navigation/MainNavigationCoordinator.java"));
        assertThat(main).contains("public void onAnnotations()", "mainNavigationCoordinator.annotations()");
        assertThat(coordinator).contains("public void annotations()", "cleanupReader();",
                "workspaceManager.showAnnotationManagerWorkspace()");
    }

}
