package com.myhomelibcorp.ui.readinglist;

import com.myhomelibcorp.application.readinglist.AddBooksToReadingListUseCase;
import com.myhomelibcorp.application.readinglist.ListReadingListsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReadingListBookActionUiService {
    private final ListReadingListsUseCase listUseCase;
    private final AddBooksToReadingListUseCase addUseCase;
    private final DialogService dialogs;
    private final ApplicationState appState;
    private final BookSelectionService bookSelectionService;

    public Menu createMenu(BookViewModel book) {
        Menu menu = new Menu(UiText.text("ui.reading_lists.add_to_list"));
        menu.setOnShowing(event -> populate(menu, book));
        return menu;
    }

    private void populate(Menu menu, BookViewModel book) {
        menu.getItems().clear();
        try {
            var lists = listUseCase.execute();
            if (lists.isEmpty()) {
                MenuItem empty = new MenuItem(UiText.text("ui.reading_lists.no_lists"));
                empty.setDisable(true);
                menu.getItems().add(empty);
                return;
            }
            for (var list : lists) {
                MenuItem item = new MenuItem(list.name());
                item.setOnAction(event -> add(book, list.id(), list.name()));
                menu.getItems().add(item);
            }
        } catch (RuntimeException failure) {
            log.warn("Cannot load reading lists for context menu", failure);
            MenuItem unavailable = new MenuItem(UiText.text("common.error"));
            unavailable.setDisable(true);
            menu.getItems().add(unavailable);
        }
    }

    private void add(BookViewModel book, String listId, String listName) {
        try {
            BookId contextBook = BookId.fromString(book.getId());
            var selected = bookSelectionService.snapshot();
            var books = selected.contains(contextBook) ? selected : java.util.List.of(contextBook);
            addUseCase.execute(listId, books,
                    appState.getCurrentLibraryCollectionId());
            appState.getStatusBar().setStatusText(UiText.text("ui.reading_lists.added") + " " + listName);
        } catch (RuntimeException failure) {
            log.error("Cannot add book {} to reading list {}", book.getId(), listId, failure);
            dialogs.showError(UiText.text("common.error"), UiExceptionSupport.message(failure));
        }
    }
}
