package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import javafx.scene.layout.Pane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceManager {

    private final FxmlLoaderFactory fxmlLoaderFactory;
    private MainController mainController;

    private final Deque<WorkspaceEntry> history = new ArrayDeque<>();
    private final Deque<WorkspaceEntry> forwardStack = new ArrayDeque<>();
    private WorkspaceEntry currentEntry;
    private Pane currentWorkspace;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public void setWorkspace(Pane workspace) {
        if (currentWorkspace != null) {
            mainController.getMainPane().getChildren().remove(currentWorkspace);
        }
        currentWorkspace = workspace;
        workspace.setMaxHeight(Double.MAX_VALUE);
        workspace.setMaxWidth(Double.MAX_VALUE);
        mainController.getMainPane().setCenter(workspace);
        mainController.updateNavigationButtons();
    }

    // Публічний метод для push (використовується з MainController)
    public void push(String type, String id) {
        WorkspaceEntry entry = new WorkspaceEntry(type, id);
        if (currentEntry != null && !currentEntry.equals(entry)) {
            history.push(currentEntry);
            forwardStack.clear();
        }
        currentEntry = entry;
        log.info("Перехід до воркспейсу: {} (id: {})", type, id);
        mainController.updateNavigationButtons();
    }

    // === Публічні методи навігації ===

    public void showDashboard() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/dashboard.fxml");
        setWorkspace(workspace);
        push("dashboard", "");
    }

    public void showAuthorWorkspace(AuthorId authorId) {
        Pane workspace = fxmlLoaderFactory.loadAuthorWorkspace(authorId);
        setWorkspace(workspace);
        push("author", authorId != null ? authorId.asString() : "");
    }

    public void showBookWorkspace(BookId bookId) {
        Pane workspace = fxmlLoaderFactory.loadBookWorkspace(bookId);
        setWorkspace(workspace);
        push("book", bookId.asString());
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(seriesId != null ? seriesId.asString() : "");
        setWorkspace(workspace);
        push("series", seriesId != null ? seriesId.asString() : "");
    }

    public void showGenreWorkspace(GenreId genreId) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(genreId != null ? genreId.asString() : "");
        setWorkspace(workspace);
        push("genre", genreId != null ? genreId.asString() : "");
    }

    public void showSearchResults(String query) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(query);
        setWorkspace(workspace);
        push("search", query != null ? query : "");
    }

    public void showCollectionWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/collection-workspace.fxml");
        setWorkspace(workspace);
        push("collection", "");
    }

    public void showGroupWorkspace(Group group) {
        Pane workspace = fxmlLoaderFactory.loadGroupWorkspace(group);
        setWorkspace(workspace);
        push("groups", group != null ? group.getId().toString() : "");
    }

    public void showReaderWorkspace(BookId bookId) {
        Pane workspace = fxmlLoaderFactory.loadReaderWorkspace(bookId);
        setWorkspace(workspace);
        push("reader", bookId.asString());
    }

    public void showImportWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/import-workspace.fxml");
        setWorkspace(workspace);
        push("import", "");
    }

    public void switchToCollection(Collection collection) {
        mainController.switchToCollection(collection);
    }

    // === Історія ===

    public void goBack() {
        if (history.isEmpty()) return;
        forwardStack.push(currentEntry);
        WorkspaceEntry previous = history.pop();
        currentEntry = previous;
        restoreWorkspace(previous);
    }

    public void goForward() {
        if (forwardStack.isEmpty()) return;
        history.push(currentEntry);
        WorkspaceEntry next = forwardStack.pop();
        currentEntry = next;
        restoreWorkspace(next);
    }

    private void restoreWorkspace(WorkspaceEntry entry) {
        switch (entry.type) {
            case "dashboard" -> showDashboard();
            case "author" -> showAuthorWorkspace(AuthorId.fromString(entry.id));
            case "series" -> showSeriesWorkspace(SeriesId.fromString(entry.id));
            case "genre" -> showGenreWorkspace(GenreId.fromCode(entry.id));
            case "book" -> showBookWorkspace(BookId.fromString(entry.id));
            case "reader" -> showReaderWorkspace(BookId.fromString(entry.id));
            case "search" -> showSearchResults(entry.id);
            case "collection" -> showCollectionWorkspace();
            case "groups" -> showGroupWorkspace(null);
            case "import" -> showImportWorkspace();
            default -> showDashboard();
        }
        mainController.updateNavigationButtons();
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    private record WorkspaceEntry(String type, String id) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WorkspaceEntry that)) return false;
            return type.equals(that.type) && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + id.hashCode();
        }
    }
}