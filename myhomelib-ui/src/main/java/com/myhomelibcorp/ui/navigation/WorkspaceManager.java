package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

@Component
@Slf4j
public class WorkspaceManager {

    private final MainController mainController;
    private final Deque<WorkspaceEntry> history = new ArrayDeque<>();
    private final Deque<WorkspaceEntry> forwardStack = new ArrayDeque<>();
    private WorkspaceEntry currentEntry;

    public WorkspaceManager(@Lazy MainController mainController) {
        this.mainController = mainController;
    }

    public void push(String type, String id) {
        WorkspaceEntry entry = new WorkspaceEntry(type, id);
        if (currentEntry != null && !currentEntry.equals(entry)) {
            history.push(currentEntry);
            forwardStack.clear();
        }
        currentEntry = entry;
        log.info("Перехід до воркспейсу: {} (id: {})", type, id);
    }

    public void goBack() {
        if (history.isEmpty()) {
            return;
        }
        forwardStack.push(currentEntry);
        WorkspaceEntry previous = history.pop();
        currentEntry = previous;
        log.info("Назад до: {} (id: {})", previous.type, previous.id);
        restoreWorkspace(previous);
    }

    public void goForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        history.push(currentEntry);
        WorkspaceEntry next = forwardStack.pop();
        currentEntry = next;
        log.info("Вперед до: {} (id: {})", next.type, next.id);
        restoreWorkspace(next);
    }

    private void restoreWorkspace(WorkspaceEntry entry) {
        switch (entry.type) {
            case "dashboard" -> mainController.showDashboard();
            case "author" -> mainController.showAuthorWorkspace(AuthorId.fromString(entry.id));
            case "series" -> mainController.showSeriesWorkspace(SeriesId.fromString(entry.id));
            case "genre" -> mainController.showGenreWorkspace(GenreId.fromCode(entry.id));
            case "book" -> mainController.showBookWorkspace(BookId.fromString(entry.id));
            case "reader" -> mainController.showReaderWorkspace(BookId.fromString(entry.id));
            case "search" -> mainController.showSearchResults(entry.id);
            case "collection" -> mainController.showCollectionWorkspace();
            case "import" -> mainController.showImportWorkspace();
            default -> mainController.showDashboard();
        }
        mainController.updateNavigationButtons();
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    public String getCurrentWorkspace() {
        return currentEntry != null ? currentEntry.type : "dashboard";
    }

    public String getCurrentId() {
        return currentEntry != null ? currentEntry.id : "";
    }

    private record WorkspaceEntry(String type, String id) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WorkspaceEntry that)) {
                return false;
            }
            return type.equals(that.type) && id.equals(that.id);
        }
        @Override
        public int hashCode() {
            return 31 * type.hashCode() + id.hashCode();
        }
    }
}