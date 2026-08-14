package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.model.Chapter;
import javafx.fxml.FXML;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
public class TOCController {

    @FXML private TreeView<Chapter> tocTreeView;

    private Consumer<Chapter> onChapterSelected;

    @FXML
    public void initialize() {
        tocTreeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Chapter item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.getTitle());
                    int indent = (item.getLevel() - 1) * 15;
                    setStyle("-fx-padding: 2 0 2 " + indent + "px;");
                }
            }
        });

        tocTreeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue() != null && onChapterSelected != null) {
                log.info("Navigating to chapter: {}", selected.getValue().getTitle());
                onChapterSelected.accept(selected.getValue());
                closeDialog();
            }
        });

        tocTreeView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<Chapter> selected = tocTreeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null && onChapterSelected != null) {
                    log.info("Navigating to chapter (double click): {}", selected.getValue().getTitle());
                    onChapterSelected.accept(selected.getValue());
                    closeDialog();
                }
            }
        });
    }

    public void setChapters(List<Chapter> chapters, Consumer<Chapter> onChapterSelected) {
        this.onChapterSelected = onChapterSelected;

        TreeItem<Chapter> root = new TreeItem<>();
        root.setValue(null);
        root.setExpanded(true);

        for (Chapter chapter : chapters) {
            TreeItem<Chapter> item = buildTreeItem(chapter);
            root.getChildren().add(item);
        }

        tocTreeView.setRoot(root);
        tocTreeView.setShowRoot(false);

        log.info("TOC loaded with {} top-level chapters", chapters.size());
    }

    private TreeItem<Chapter> buildTreeItem(Chapter chapter) {
        TreeItem<Chapter> item = new TreeItem<>(chapter);
        item.setExpanded(true);

        if (chapter.getChildren() != null && !chapter.getChildren().isEmpty()) {
            for (Chapter child : chapter.getChildren()) {
                item.getChildren().add(buildTreeItem(child));
            }
        }

        return item;
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) tocTreeView.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}