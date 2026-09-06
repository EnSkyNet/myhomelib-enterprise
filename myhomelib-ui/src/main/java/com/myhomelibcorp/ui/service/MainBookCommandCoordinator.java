package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Stage 25A book-command orchestration extracted from MainController. */
@Component
@RequiredArgsConstructor
public class MainBookCommandCoordinator {

    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final ClassicLibraryActionsService classicActions;
    private final ExternalBookLauncher externalBookLauncher;
    private final WorkspaceManager workspaceManager;

    public boolean hasSelectedBook() {
        return currentBook() != null;
    }

    /** Canonical current-book observable used by menu/shortcut context refresh across all workspaces. */
    public ObjectProperty<BookDto> selectedBookProperty() {
        return appState.getBookDetails().currentBookProperty();
    }

    public void editMetadata(Window owner, Runnable refresh) {
        BookDto selected = requireBook();
        if (selected == null) return;
        classicActions.editBook(owner, BookId.fromString(selected.getId()), refresh);
    }

    public void deleteBook(Runnable refresh) {
        BookDto selected = requireBook();
        if (selected == null) return;
        if (classicActions.deleteBook(BookId.fromString(selected.getId()))) {
            appState.getBookDetails().setCurrentBook(null);
            refresh.run();
        }
    }

    public void openInternal() {
        BookDto selected = requireBook();
        if (selected != null) workspaceManager.showNewReaderWorkspace(BookId.fromString(selected.getId()));
    }

    public void download() {
        BookDto selected = requireBook();
        if (selected != null) bookDownloadCoordinator.ensureLocal(selected);
    }

    public void removeLocalCopy(Runnable refresh) {
        BookDto selected = requireBook();
        if (selected == null) return;
        bookDownloadCoordinator.removeLocalCopy(selected).whenComplete((count, error) -> {
            if (error == null) Platform.runLater(refresh);
        });
    }

    public void cancelDownload() {
        BookDto selected = currentBook();
        if (selected == null || !bookDownloadCoordinator.cancel(selected)) {
            dialogService.showInfo("Завантаження", "Для вибраної книги активного завантаження немає.");
        }
    }

    public void openExternal() {
        BookDto selected = requireBook();
        if (selected == null) return;
        bookDownloadCoordinator.ensureLocalForOpen(selected).whenComplete((path, error) -> {
            if (error != null) return;
            try {
                externalBookLauncher.open(selected);
            } catch (Exception ex) {
                Platform.runLater(() -> dialogService.showError("Зовнішня читалка", ex.getMessage()));
            }
        });
    }

    public void openInNewReader(BookId bookId) {
        if (bookId != null) workspaceManager.showNewReaderWorkspace(bookId);
    }

    private BookDto requireBook() {
        BookDto selected = currentBook();
        if (selected == null) dialogService.showWarning("Немає книги", "Спочатку виберіть книгу.");
        return selected;
    }

    private BookDto currentBook() {
        return appState.getBookDetails().getCurrentBook();
    }
}
