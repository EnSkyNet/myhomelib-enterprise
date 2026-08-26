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
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.HelpTopicRegistry;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.table.BookTableController;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
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
    private final HelpTopicRegistry helpTopicRegistry;

    private final ReadOnlyBooleanWrapper canGoBack = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper canGoForward = new ReadOnlyBooleanWrapper(false);
    private StackPane workspaceStackPane;
    private Pane currentWorkspace;
    private WorkspaceLifecycle currentLifecycle;

    private final Deque<WorkspaceEntry> history = new ArrayDeque<>();
    private final Deque<WorkspaceEntry> forwardStack = new ArrayDeque<>();
    private WorkspaceEntry currentEntry;

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

        refreshNavigationState();
    }

    public void push(String type, String id) {
        WorkspaceEntry entry = new WorkspaceEntry(type, id);
        if (currentEntry != null && !currentEntry.equals(entry)) {
            history.push(currentEntry);
            forwardStack.clear();
        }
        currentEntry = entry;
        log.info("Перехід до воркспейсу: {} (id: {})", type, id);
        refreshNavigationState();
    }

    // ==================== Завантаження воркспейсів ====================

    private Pane loadBookTableWorkspace(String profileKey) {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/book-table.fxml");
        if (workspace.getUserData() instanceof BookTableController controller) {
            controller.setProfileKey(profileKey);
        }
        return workspace;
    }

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
        Pane workspace = loadBookTableWorkspace("series");
        setWorkspace(workspace, "series");
        push("series", seriesId.asString());
        bookLoaderService.loadBooksBySeries(seriesId);
    }

    public void showGenreWorkspace(GenreId genreId) {
        if (genreId == null) throw new IllegalArgumentException("GenreId не може бути null");
        Pane workspace = loadBookTableWorkspace("genre");
        setWorkspace(workspace, "genre");
        push("genre", genreId.asString());
        bookLoaderService.loadBooksByGenre(genreId);
    }

    public void showYearWorkspace(int year) {
        if (year <= 0) throw new IllegalArgumentException("year must be positive");
        Pane workspace = loadBookTableWorkspace("year");
        setWorkspace(workspace, "year");
        push("year", Integer.toString(year));
        bookLoaderService.loadBooksByYear(year);
    }

    public void showLanguageWorkspace(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new IllegalArgumentException("languageCode cannot be blank");
        }
        Pane workspace = loadBookTableWorkspace("language");
        setWorkspace(workspace, "language");
        push("language", languageCode);
        bookLoaderService.loadBooksByLanguage(languageCode);
    }

    public void showArchiveWorkspace(ArchiveNavigationKey archive) {
        if (archive == null) throw new IllegalArgumentException("archive cannot be null");
        Pane workspace = loadBookTableWorkspace("archive");
        setWorkspace(workspace, "archive");
        push("archive", archive.encode());
        bookLoaderService.loadBooksByArchive(archive);
    }

    public void showKeywordWorkspace(String keyword) {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword cannot be blank");
        Pane workspace = loadBookTableWorkspace("keyword");
        setWorkspace(workspace, "keyword");
        push("keyword", keyword);
        bookLoaderService.loadBooksByKeyword(keyword);
    }

    public void showGroupBooksWorkspace(GroupId groupId) {
        if (groupId == null || groupId.asLong() == null) throw new IllegalArgumentException("groupId cannot be null");
        Pane workspace = loadBookTableWorkspace("group-nav");
        setWorkspace(workspace, "group-nav");
        push("group-nav", groupId.toString());
        bookLoaderService.loadBooksByGroup(groupId);
    }

    public void showReviewsWorkspace(ReviewNavigationFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter cannot be null");
        Pane workspace = loadBookTableWorkspace("reviews");
        setWorkspace(workspace, "reviews");
        push("reviews", filter.id());
        bookLoaderService.loadBooksByReviewSubset(filter);
    }

    public void showAllBooksWorkspace() {
        Pane workspace = loadBookTableWorkspace("all-books");
        setWorkspace(workspace, "all-books");
        push("all-books", "");
        bookLoaderService.loadAllBooks();
    }

    public void showUpdatesWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/updates-workspace.fxml");
        setWorkspace(workspace, "updates");
        push("updates", "");
    }

    public void showAlreadyReadWorkspace() {
        Pane workspace = loadBookTableWorkspace("already-read");
        setWorkspace(workspace, "already-read");
        push("already-read", "");
        bookLoaderService.loadAlreadyReadBooks();
    }

    public void showHistoryWorkspace() {
        Pane workspace = loadBookTableWorkspace("history");
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

    /** Disposes the active reader before a navigation action without touching non-reader workspaces. */
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
        refreshNavigationState();
    }

    public void goForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        history.push(currentEntry);
        WorkspaceEntry next = forwardStack.pop();
        currentEntry = next;
        restoreWorkspace(next);
        refreshNavigationState();
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
            case "updates" -> showUpdatesWorkspace();
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
        refreshNavigationState();
    }


    public String currentHelpTopic() {
        return helpTopicRegistry.topicForWorkspace(currentEntry == null ? "dashboard" : currentEntry.type);
    }


    public ReadOnlyBooleanProperty canGoBackProperty() {
        return canGoBack.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canGoForwardProperty() {
        return canGoForward.getReadOnlyProperty();
    }

    private void refreshNavigationState() {
        canGoBack.set(!history.isEmpty());
        canGoForward.set(!forwardStack.isEmpty());
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
