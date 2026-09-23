package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level ratchet for UI async wiring. UiAsyncRequestGuard has separate behavioral tests;
 * this contract prevents the two formerly synchronous workspace loads from drifting back onto
 * the JavaFX thread or dropping their stale-result/loading-state guards.
 */
class AsyncWorkspaceControllerContractTest {

    @Test
    void bookWorkspaceKeepsDatabaseLoadOffFxThreadAndRejectsStaleResults() throws IOException {
        String source = source("book/BookWorkspaceController.java");

        assertThat(source)
                .contains("backgroundExecutor.submitCollectionScopedCancellable")
                .contains("loadBookByIdUseCase.execute(bookId)")
                .contains("previous.cancel(true)")
                .contains("UiAsyncRequestGuard.next(loadGeneration, appState)")
                .contains("UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)")
                .contains("setLoadState(UiText.text(\"ui.fxml.book_workspace.loading\"), true)")
                .contains("setLoadState(UiText.text(\"ui.java.book_workspace_controller.457c748374\"), true)")
                .contains("setLoadState(UiText.text(\"ui.java.book_workspace_controller.b1ab2d5954\"), true)");

        assertThat(source.indexOf("backgroundExecutor.submitCollectionScopedCancellable"))
                .isLessThan(source.indexOf("loadBookByIdUseCase.execute(bookId)"));
    }

    @Test
    void groupWorkspaceKeepsGroupLoadOffFxThreadAndGuardsCollectionSwitch() throws IOException {
        String source = source("group/GroupWorkspaceController.java");

        assertThat(source)
                .contains("executor.submitCollectionScoped(LibraryOperationType.QUERY, loadGroupsUseCase::execute)")
                .contains("UiAsyncRequestGuard.next(groupListGeneration, appState)")
                .contains("UiAsyncRequestGuard.isCurrent(requestToken, groupListGeneration, appState)")
                .contains("appState.currentLibraryCollectionProperty()")
                .contains("UiAsyncRequestGuard.invalidate(groupListGeneration)")
                .contains("setGroupsState(UiText.text(\"ui.fxml.book_workspace.loading\"), true, true)")
                .contains("setGroupsState(UiText.text(\"ui.java.group_workspace_controller.6b88e66ffc\"), true, false)")
                .contains("UiText.text(\"ui.java.group_workspace_controller.f8e3a3637c\")")
                .contains("requestedGroupId = group.getId().asLong()")
                .contains("selectRequestedGroup()");
    }

    private static String source(String relative) throws IOException {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui").resolve(relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
