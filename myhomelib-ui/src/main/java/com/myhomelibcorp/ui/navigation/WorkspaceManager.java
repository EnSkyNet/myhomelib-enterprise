package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.application.usecase.group.LoadGroupUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.HelpTopicRegistry;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.table.BookTableController;
import javafx.application.Platform;
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
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final HelpTopicRegistry helpTopicRegistry;
    private final LoadGroupUseCase loadGroupUseCase;
    private final SessionService sessionService;

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

    public void setWorkspace(Pane workspace) {
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
        push(new WorkspaceEntry(type, id, null), true);
    }

    private void push(WorkspaceEntry entry, boolean persist) {
        if (currentEntry != null && !currentEntry.sameLocation(entry)) {
            history.push(currentEntry);
            forwardStack.clear();
        }
        currentEntry = entry;
        if (persist && entry.isPersistable()) {
            sessionService.saveWorkspaceState(entry.type, entry.id);
        }
        log.info("Перехід до воркспейсу: {} (id: {})", entry.type, entry.id);
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
        push("book", bookId != null ? bookId.asString() : "");
    }

    public void showSeriesWorkspace(SeriesId seriesId) {
        if (seriesId == null) throw new IllegalArgumentException("SeriesId не може бути null");
        Pane workspace = loadBookTableWorkspace("series");
        setWorkspace(workspace);
        push("series", seriesId.asString());
        bookLoaderService.loadBooksBySeries(seriesId);
    }

    public void showGenreWorkspace(GenreId genreId) {
        if (genreId == null) throw new IllegalArgumentException("GenreId не може бути null");
        Pane workspace = loadBookTableWorkspace("genre");
        setWorkspace(workspace);
        push("genre", genreId.asString());
        bookLoaderService.loadBooksByGenre(genreId);
    }

    public void showYearWorkspace(int year) {
        if (year <= 0) throw new IllegalArgumentException("year must be positive");
        Pane workspace = loadBookTableWorkspace("year");
        setWorkspace(workspace);
        push("year", Integer.toString(year));
        bookLoaderService.loadBooksByYear(year);
    }

    public void showLanguageWorkspace(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new IllegalArgumentException("languageCode cannot be blank");
        }
        Pane workspace = loadBookTableWorkspace("language");
        setWorkspace(workspace);
        push("language", languageCode);
        bookLoaderService.loadBooksByLanguage(languageCode);
    }

    public void showPublisherWorkspace(String publisher) {
        if (publisher == null || publisher.isBlank()) throw new IllegalArgumentException("publisher cannot be blank");
        Pane workspace = loadBookTableWorkspace("publisher");
        setWorkspace(workspace);
        push("publisher", publisher);
        bookLoaderService.loadBooksByPublisher(publisher);
    }

    public void showArchiveWorkspace(ArchiveNavigationKey archive) {
        if (archive == null) throw new IllegalArgumentException("archive cannot be null");
        Pane workspace = loadBookTableWorkspace("archive");
        setWorkspace(workspace);
        push("archive", archive.encode());
        bookLoaderService.loadBooksByArchive(archive);
    }

    public void showKeywordWorkspace(String keyword) {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword cannot be blank");
        Pane workspace = loadBookTableWorkspace("keyword");
        setWorkspace(workspace);
        push("keyword", keyword);
        bookLoaderService.loadBooksByKeyword(keyword);
    }

    public void showGroupBooksWorkspace(GroupId groupId) {
        if (groupId == null || groupId.asLong() == null) throw new IllegalArgumentException("groupId cannot be null");
        Pane workspace = loadBookTableWorkspace("group-nav");
        setWorkspace(workspace);
        push("group-nav", groupId.toString());
        bookLoaderService.loadBooksByGroup(groupId);
    }

    public void showReviewsWorkspace(ReviewNavigationFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter cannot be null");
        Pane workspace = loadBookTableWorkspace("reviews");
        setWorkspace(workspace);
        push("reviews", filter.id());
        bookLoaderService.loadBooksByReviewSubset(filter);
    }

    public void showAllBooksWorkspace() {
        Pane workspace = loadBookTableWorkspace("all-books");
        setWorkspace(workspace);
        push("all-books", "");
        bookLoaderService.loadAllBooks();
    }

    public void showUpdatesWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/updates-workspace.fxml");
        setWorkspace(workspace);
        push("updates", "");
    }

    public void showAlreadyReadWorkspace() {
        Pane workspace = loadBookTableWorkspace("already-read");
        setWorkspace(workspace);
        push("already-read", "");
        bookLoaderService.loadAlreadyReadBooks();
    }

    public void showHistoryWorkspace() {
        Pane workspace = loadBookTableWorkspace("history");
        setWorkspace(workspace);
        push("history", "");
        bookLoaderService.loadReadingHistory();
    }

    public void showSearchResults(String query) {
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(query);
        setWorkspace(workspace);
        push("search", query != null ? query : "");
    }

    public void showSearchResults(List<BookDto> results) {
        List<BookDto> snapshot = results == null ? List.of() : List.copyOf(results);
        Pane workspace = fxmlLoaderFactory.loadSearchWorkspace(snapshot);
        setWorkspace(workspace);
        push(new WorkspaceEntry("search-results", Integer.toString(snapshot.size()), snapshot), false);
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


    /**
     * Opens the Reader only after the physical book resource is available.
     * This is the single guarded entry point so recent/history/programmatic
     * navigation cannot bypass the missing-book download confirmation.
     */
    public void showNewReaderWorkspace(BookId bookId) {
        if (bookId == null) return;
        bookDownloadCoordinator.ensureLocalForOpen(bookId).whenComplete((path, error) -> {
            if (error != null) {
                log.warn("Не вдалося відкрити Reader для книги {}: {}", bookId, error.getMessage());
                return;
            }
            Runnable open = () -> openNewReaderWorkspaceLocal(bookId);
            if (Platform.isFxApplicationThread()) open.run();
            else Platform.runLater(open);
        });
    }

    private void openNewReaderWorkspaceLocal(BookId bookId) {
        Pane workspace = fxmlLoaderFactory.loadNewReaderWorkspace(bookId);
        setWorkspace(workspace);
        push("new-reader", bookId.asString());
    }

    public void showImportWorkspace() {
        Pane workspace = fxmlLoaderFactory.loadWorkspace("/view/import-workspace.fxml");
        setWorkspace(workspace);
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
            case "publisher" -> showPublisherWorkspace(entry.id);
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
            case "search-results" -> showSearchResults(entry.results == null ? List.of() : entry.results);
            case "collection" -> showCollectionWorkspace();
            case "groups" -> {
                Long groupId = parseLong(entry.id);
                if (groupId == null) showGroupWorkspace(null);
                else loadGroupUseCase.execute(groupId)
                        .ifPresentOrElse(this::showGroupWorkspace, this::showCollectionWorkspace);
            }
            case "import" -> showImportWorkspace();
            default -> showDashboard();
        }
        refreshNavigationState();
    }


    public String currentHelpTopic() {
        String type = currentEntry == null ? "dashboard" : currentEntry.type;
        if ("search-results".equals(type)) type = "search";
        return helpTopicRegistry.topicForWorkspace(type);
    }

    /** Restore a persisted workspace without restoring stale in-memory history. */
    public void restoreSessionWorkspace(SessionService.WorkspaceState state) {
        history.clear();
        forwardStack.clear();
        if (state == null || state.type().isBlank()) {
            showDashboard();
            return;
        }
        try {
            restoreWorkspace(new WorkspaceEntry(state.type(), state.id(), null));
            history.clear();
            forwardStack.clear();
            refreshNavigationState();
        } catch (RuntimeException ex) {
            log.warn("Не вдалося відновити workspace {}:{}; відкриваємо dashboard", state.type(), state.id(), ex);
            history.clear();
            forwardStack.clear();
            showDashboard();
        }
    }

    private static Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (Exception ignored) { return null; }
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

    private record WorkspaceEntry(String type, String id, List<BookDto> results) {
        private WorkspaceEntry {
            type = type == null ? "dashboard" : type;
            id = id == null ? "" : id;
            results = results == null ? null : List.copyOf(results);
        }

        boolean sameLocation(WorkspaceEntry other) {
            return other != null && type.equals(other.type) && id.equals(other.id);
        }

        boolean isPersistable() {
            return !"search-results".equals(type);
        }
    }
}
