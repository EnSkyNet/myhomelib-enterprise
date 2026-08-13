package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.model.Chapter;
import javafx.fxml.FXML;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.Consumer;

public class TOCController {

    @FXML private ListView<Chapter> tocListView;

    private Consumer<Chapter> onChapterSelected;

    @FXML
    public void initialize() {
        tocListView.setCellFactory(lv -> new ListCell<>() {
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

        tocListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Chapter selected = tocListView.getSelectionModel().getSelectedItem();
                if (selected != null && onChapterSelected != null) {
                    onChapterSelected.accept(selected);
                }
            }
        });
    }

    public void setChapters(List<Chapter> chapters, Consumer<Chapter> onChapterSelected) {
        this.onChapterSelected = onChapterSelected;
        tocListView.getItems().setAll(chapters);
    }

    @FXML
    private void onClose() {
        Stage stage = (Stage) tocListView.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}