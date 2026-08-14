package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class BookmarksController {

    @FXML private ListView<Bookmark> bookmarksListView;

    private Consumer<Bookmark> onBookmarkSelected;
    private Consumer<Bookmark> onBookmarkDeleted;

    @FXML
    public void initialize() {
        bookmarksListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Bookmark item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String text = item.getTitle();
                    if (item.getChapterTitle() != null && !item.getChapterTitle().isEmpty()) {
                        text += " (" + item.getChapterTitle() + ")";
                    }
                    setText(text);
                    if (item.getFormattedDate() != null && !item.getFormattedDate().isEmpty()) {
                        setTooltip(new javafx.scene.control.Tooltip("Створено: " + item.getFormattedDate()));
                    }
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
        MenuItem goToItem = new MenuItem("Перейти до закладки");
        goToItem.setOnAction(e -> {
            Bookmark selected = bookmarksListView.getSelectionModel().getSelectedItem();
            if (selected != null && onBookmarkSelected != null) {
                onBookmarkSelected.accept(selected);
                closeDialog();
            }
        });

        MenuItem deleteItem = new MenuItem("Видалити");
        deleteItem.setStyle("-fx-text-fill: #d32f2f;");
        deleteItem.setOnAction(e -> {
            Bookmark selected = bookmarksListView.getSelectionModel().getSelectedItem();
            if (selected != null && onBookmarkDeleted != null) {
                onBookmarkDeleted.accept(selected);
                bookmarksListView.getItems().remove(selected);
                if (bookmarksListView.getItems().isEmpty()) {
                    closeDialog();
                }
            }
        });

        contextMenu.getItems().addAll(goToItem, deleteItem);
        bookmarksListView.setContextMenu(contextMenu);
    }

    public void setBookmarks(List<Bookmark> bookmarks,
                             Consumer<Bookmark> onBookmarkSelected,
                             Consumer<Bookmark> onBookmarkDeleted) {
        this.onBookmarkSelected = onBookmarkSelected;
        this.onBookmarkDeleted = onBookmarkDeleted;
        bookmarksListView.getItems().setAll(bookmarks);
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