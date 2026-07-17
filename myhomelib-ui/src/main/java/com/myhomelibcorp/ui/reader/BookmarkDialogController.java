package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.model.Bookmark;
import com.myhomelibcorp.reader.service.BookmarkManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class BookmarkDialogController {

    private final BookmarkManager bookmarkManager;

    @FXML private ListView<Bookmark> bookmarksListView;

    private String bookId;
    private Consumer<Bookmark> onBookmarkSelected;

    @FXML
    public void initialize() {
        bookmarksListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Bookmark item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getDisplayText());
                    setTooltip(new Tooltip(item.getContext() != null ? item.getContext() : "Без контексту"));
                }
            }
        });

        bookmarksListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Bookmark selected = bookmarksListView.getSelectionModel().getSelectedItem();
                if (selected != null && onBookmarkSelected != null) {
                    onBookmarkSelected.accept(selected);
                    closeDialog();
                }
            }
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("🗑 Видалити");
        deleteItem.setOnAction(e -> {
            Bookmark selected = bookmarksListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                bookmarkManager.removeBookmark(bookId, selected.getId());
                loadBookmarks();
            }
        });
        contextMenu.getItems().add(deleteItem);
        bookmarksListView.setContextMenu(contextMenu);
    }

    public void setBookId(String bookId, Consumer<Bookmark> onBookmarkSelected) {
        this.bookId = bookId;
        this.onBookmarkSelected = onBookmarkSelected;
        loadBookmarks();
    }

    private void loadBookmarks() {
        List<Bookmark> bookmarks = bookmarkManager.getBookmarks(bookId);
        Platform.runLater(() -> {
            bookmarksListView.getItems().setAll(bookmarks);
            if (bookmarks.isEmpty()) {
                bookmarksListView.setPlaceholder(new Label("Немає закладок для цієї книги"));
            }
        });
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) bookmarksListView.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}