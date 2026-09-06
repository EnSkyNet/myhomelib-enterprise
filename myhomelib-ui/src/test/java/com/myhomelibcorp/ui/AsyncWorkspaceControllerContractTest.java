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
                .contains("backgroundExecutor.submitCancellable")
                .contains("loadBookByIdUseCase.execute(bookId)")
                .contains("previous.cancel(true)")
                .contains("UiAsyncRequestGuard.next(loadGeneration, appState)")
                .contains("UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)")
                .contains("setLoadState(\"Завантаження…\", true)")
                .contains("setLoadState(\"Книгу не знайдено\", true)")
                .contains("setLoadState(\"Не вдалося завантажити книгу\", true)");

        assertThat(source.indexOf("backgroundExecutor.submitCancellable"))
                .isLessThan(source.indexOf("loadBookByIdUseCase.execute(bookId)"));
    }

    @Test
    void groupWorkspaceKeepsGroupLoadOffFxThreadAndGuardsCollectionSwitch() throws IOException {
        String source = source("group/GroupWorkspaceController.java");

        assertThat(source)
                .contains("executor.submit(loadGroupsUseCase::execute)")
                .contains("UiAsyncRequestGuard.next(groupListGeneration, appState)")
                .contains("UiAsyncRequestGuard.isCurrent(requestToken, groupListGeneration, appState)")
                .contains("appState.currentLibraryCollectionProperty()")
                .contains("UiAsyncRequestGuard.invalidate(groupListGeneration)")
                .contains("setGroupsState(\"Завантаження…\", true, true)")
                .contains("setGroupsState(\"Груп немає\", true, false)")
                .contains("Не вдалося завантажити групи")
                .contains("requestedGroupId = group.getId().asLong()")
                .contains("selectRequestedGroup()");
    }

    private static String source(String relative) throws IOException {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui").resolve(relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
