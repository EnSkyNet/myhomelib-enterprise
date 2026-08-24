package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceManager {

    private final FxmlLoaderFactory fxmlLoaderFactory;
    private final LocalizationService localizationService;
    private final BookLoaderService bookLoaderService;

    private MainController mainController;
    private StackPane workspaceStackPane;
    private Pane currentWorkspace;
    private WorkspaceLifecycle currentLifecycle;

    private final Deque<WorkspaceEntry> history = new ArrayDeque<>();
    private final Deque<WorkspaceEntry> forwardStack = new ArrayDeque<>();
    private WorkspaceEntry currentEntry;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public void init(StackPane stackPane) {
        this.workspaceStackPane = stackPane;
    }

    private void disposeCurrentWorkspace() {
        if (currentWorkspace != null) {
            Object controller = currentWorkspace.getUserData();
            if (controller instanceof WorkspaceLifecycle lifecycle) {
                log.info("Disposing workspace lifecycle: {}", controller.getClass().getSimpleName());
                lifecycle.dispose();
            }

            workspaceStackPane.getChildren().remove(currentWorkspace);
            currentWorkspace = null;
            currentLifecycle = null;
        }
    }

    public void setWorkspace(Pane workspace, String type) {
        disposeCurrentWorkspace();

        if (workspaceStackPane == null) {
            log.error("WorkspaceStackPane не ініціалізовано!");
            return;
        }

        currentWorkspace = workspace;
        localizationService.apply(workspace);
        workspace.setMaxHeight(Double.MAX_VALUE);
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspaceStackPane.getChildren().add(workspace);

        if (mainController != null) {
            mainController.updateNavigationButtons();
        }
    }

    public void push(String type, String id) {
        WorkspaceEntry entry = new WorkspaceEntry(type, id);
        if (currentEntry != null && !currentEntry.equals(entry)) {
            history.push(currentEntry);
            forwardStack.clear();
        }
        currentEntry = entry;
        log.info("Перехід до воркспейсу: {} (id: {})", type, id);
        if (mainController != null) {
            mainController.updateNavigationButtons();
        }
    }

    // ==================== Завантаження воркспейсів ====================

    public void showDashboard() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/dashboard.fxml");
        setWorkspace(workspace, "dashboard");
        push("dashboard", "");
    }

    public void showAuthorWorkspace(AuthorId authorId) {
        Pane workspace = fxmlLoaderFactory.loadAuthorWorkspace(authorId);
        setWorkspace(workspace, "author");
        push("author", authorId != null ? authorId.asString() : "");
    }

    public void showBookWorkspace(BookId bookId) {
        Pane workspace = fxmlLoaderFactory.loadBookWorkspace(bookId);
        setWorkspace(workspace, "book");
        push("book", bookId != null ? bookId.asString() : "");
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        if (seriesId == null) throw new IllegalArgumentException("SeriesId не може бути null");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "series");
        push("series", seriesId.asString());
        bookLoaderService.loadBooksBySeries(seriesId);
    }

    public void showGenreWorkspace(GenreId genreId) {
        if (genreId == null) throw new IllegalArgumentException("GenreId не може бути null");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "genre");
        push("genre", genreId.asString());
        bookLoaderService.loadBooksByGenre(genreId);
    }

    public void showYearWorkspace(int year) {
        if (year <= 0) throw new IllegalArgumentException("year must be positive");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "year");
        push("year", Integer.toString(year));
        bookLoaderService.loadBooksByYear(year);
    }

    public void showLanguageWorkspace(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new IllegalArgumentException("languageCode cannot be blank");
        }
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "language");
        push("language", languageCode);
        bookLoaderService.loadBooksByLanguage(languageCode);
    }

    public void showArchiveWorkspace(ArchiveNavigationKey archive) {
        if (archive == null) throw new IllegalArgumentException("archive cannot be null");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "archive");
        push("archive", archive.encode());
        bookLoaderService.loadBooksByArchive(archive);
    }

    public void showKeywordWorkspace(String keyword) {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword cannot be blank");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "keyword");
        push("keyword", keyword);
        bookLoaderService.loadBooksByKeyword(keyword);
    }

    public void showGroupBooksWorkspace(GroupId groupId) {
        if (groupId == null || groupId.asLong() == null) throw new IllegalArgumentException("groupId cannot be null");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "group-nav");
        push("group-nav", groupId.toString());
        bookLoaderService.loadBooksByGroup(groupId);
    }

    public void showReviewsWorkspace(ReviewNavigationFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter cannot be null");
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "reviews");
        push("reviews", filter.id());
        bookLoaderService.loadBooksByReviewSubset(filter);
    }

    public void showAllBooksWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "all-books");
        push("all-books", "");
        bookLoaderService.loadAllBooks();
    }

    public void showAlreadyReadWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "already-read");
        push("already-read", "");
        bookLoaderService.loadAlreadyReadBooks();
    }

    public void showHistoryWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        setWorkspace(workspace, "history");
        push("history", "");
        bookLoaderService.loadReadingHistory();
    }

    public void showSearchResults(String query) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(query);
        setWorkspace(workspace, "search");
        push("search", query != null ? query : "");
    }

    public void showSearchResults(List<BookDto> results) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(results);
        setWorkspace(workspace, "search");
        push("search", "results_" + (results != null ? results.size() : 0));
    }

    public void showCollectionWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/collection-workspace.fxml");
        setWorkspace(workspace, "collection");
        push("collection", "");
    }

    public void showGroupWorkspace(Group group) {
        Pane workspace = fxmlLoaderFactory.loadGroupWorkspace(group);
        setWorkspace(workspace, "groups");
        push("groups", group != null ? group.getId().toString() : "");
    }

    /** Compatibility alias retained for old navigation entries. */
    public void showReaderWorkspace(BookId bookId) {
        showNewReaderWorkspace(bookId);
    }

    /**
     * НОВИЙ МЕТОД: показує новий Reader Workspace (без WebView).
     */
    public void showNewReaderWorkspace(BookId bookId) {
        Pane workspace = fxmlLoaderFactory.loadNewReaderWorkspace(bookId);
        setWorkspace(workspace, "new-reader");
        push("new-reader", bookId != null ? bookId.asString() : "");
    }

    public void showImportWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/import-workspace.fxml");
        setWorkspace(workspace, "import");
        push("import", "");
    }

    /** Dispose the active workspace, including Reader position/resources. */
    public void disposeCurrent() {
        disposeCurrentWorkspace();
    }

    /** Used by MainController before navigation; avoids disposing non-reader screens unnecessarily. */
    public void disposeCurrentReaderIfActive() {
        if (currentEntry != null && ("reader".equals(currentEntry.type) || "new-reader".equals(currentEntry.type))) {
            disposeCurrentWorkspace();
        }
    }

    // ==================== Навігація ====================

    public void goBack() {
        if (history.isEmpty()) {
            return;
        }
        forwardStack.push(currentEntry);
        WorkspaceEntry previous = history.pop();
        currentEntry = previous;
        restoreWorkspace(previous);
    }

    public void goForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
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
            case "year" -> showYearWorkspace(Integer.parseInt(entry.id));
            case "language" -> showLanguageWorkspace(entry.id);
            case "archive" -> showArchiveWorkspace(ArchiveNavigationKey.decode(entry.id));
            case "keyword" -> showKeywordWorkspace(entry.id);
            case "group-nav" -> showGroupBooksWorkspace(GroupId.fromLong(Long.parseLong(entry.id)));
            case "reviews" -> showReviewsWorkspace(ReviewNavigationFilter.fromId(entry.id));
            case "all-books" -> showAllBooksWorkspace();
            case "already-read" -> showAlreadyReadWorkspace();
            case "history" -> showHistoryWorkspace();
            case "book" -> showBookWorkspace(BookId.fromString(entry.id));
            case "reader" -> showNewReaderWorkspace(BookId.fromString(entry.id)); // Перенаправляємо на новий Reader
            case "new-reader" -> showNewReaderWorkspace(BookId.fromString(entry.id));
            case "search" -> showSearchResults(entry.id);
            case "collection" -> showCollectionWorkspace();
            case "groups" -> showGroupWorkspace(null);
            case "import" -> showImportWorkspace();
            default -> showDashboard();
        }
        if (mainController != null) {
            mainController.updateNavigationButtons();
        }
    }


    public String currentHelpTopic() {
        if (currentEntry == null) return "index";
        return switch (currentEntry.type) {
            case "search", "series", "genre", "author", "year", "language", "archive", "keyword", "group-nav", "reviews", "already-read", "history", "all-books" -> "search";
            case "new-reader", "reader", "book" -> "reader";
            case "import" -> "import";
            case "collection" -> "collections";
            default -> "index";
        };
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    // ==================== Внутрішній клас ====================

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
