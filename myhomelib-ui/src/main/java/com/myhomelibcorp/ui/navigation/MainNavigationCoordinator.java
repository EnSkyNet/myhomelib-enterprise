package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.service.ReadingHistoryService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Stage 25A navigation/menu orchestration extracted from MainController.
 * Keeps FXML handlers thin while WorkspaceManager remains the single owner of workspace history.
 */
@Component
@RequiredArgsConstructor
public class MainNavigationCoordinator {

    private final WorkspaceManager workspaceManager;
    private final NavigationPanelController navigationPanelController;
    private final ClassicLibraryActionsService classicActions;
    private final ReadingHistoryService readingHistoryService;
    private final DialogService dialogService;
    private final LocalizationService localizationService;

    public void cleanupReader() {
        workspaceManager.disposeCurrentReaderIfActive();
    }

    public void authors() { cleanupReader(); navigationPanelController.onAuthors(); }
    public void series() { cleanupReader(); navigationPanelController.onSeries(); }
    public void genres() { cleanupReader(); navigationPanelController.onGenres(); }
    public void allBooks() { cleanupReader(); navigationPanelController.onAllBooks(); }
    public void collections() { cleanupReader(); workspaceManager.showCollectionWorkspace(); }

    public void newBooks() {
        cleanupReader();
        workspaceManager.showSearchResults(classicActions.newBooks(500));
    }

    public void updates() {
        cleanupReader();
        navigationPanelController.revealNode(NavigationMode.UPDATES, "updates");
        workspaceManager.showUpdatesWorkspace();
    }

    public void followedAuthors() {
        cleanupReader();
        workspaceManager.showFollowedAuthorsWorkspace();
    }

    public void alreadyRead() {
        cleanupReader();
        navigationPanelController.revealNode(NavigationMode.ALREADY_READ, "already-read");
        workspaceManager.showAlreadyReadWorkspace();
    }

    public void history() {
        cleanupReader();
        navigationPanelController.revealNode(NavigationMode.HISTORY, "history");
        workspaceManager.showHistoryWorkspace();
    }

    public void search(String query) {
        cleanupReader();
        workspaceManager.showSearchResults(query == null ? "" : query);
    }

    public void importWorkspace() {
        cleanupReader();
        workspaceManager.showImportWorkspace();
    }

    public void clearHistory(Menu recentBooksMenu) {
        if (!dialogService.showConfirmation(
                "Очистити історію читання?",
                "Список недавніх книг та історію читання буде очищено.",
                "Позиція читання, закладки та позначка «Прочитано» залишаться без змін.")) {
            return;
        }
        readingHistoryService.clear();
        navigationPanelController.refreshAll();
        populateRecentBooksMenu(recentBooksMenu);
        if (navigationPanelController.getCurrentMode() == NavigationMode.HISTORY) {
            workspaceManager.showHistoryWorkspace();
        }
    }

    public void populateRecentBooksMenu(Menu recentBooksMenu) {
        if (recentBooksMenu == null) return;
        recentBooksMenu.getItems().clear();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        var recent = readingHistoryService.recent(12);
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem(localizationService.tr("Історія порожня"));
            empty.setDisable(true);
            recentBooksMenu.getItems().add(empty);
            return;
        }
        for (var item : recent) {
            MenuItem menuItem = new MenuItem(item.book().getTitle() + " — " + item.lastOpenedAt().format(formatter));
            menuItem.setOnAction(event -> {
                cleanupReader();
                workspaceManager.showNewReaderWorkspace(BookId.fromString(item.book().getId()));
            });
            recentBooksMenu.getItems().add(menuItem);
        }
    }
}
