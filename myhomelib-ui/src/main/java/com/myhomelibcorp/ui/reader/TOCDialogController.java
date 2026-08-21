package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ChapterIndex;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
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
public class TOCDialogController {

    @FXML
    private ListView<ChapterIndex> tocListView;

    private Consumer<ChapterIndex> onChapterSelected;

    @FXML
    public void initialize() {
        tocListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ChapterIndex item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.title());
                }
            }
        });

        tocListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ChapterIndex selected = tocListView.getSelectionModel().getSelectedItem();
                if (selected != null && onChapterSelected != null) {
                    onChapterSelected.accept(selected);
                    closeDialog();
                }
            }
        });
    }

    public void setChapters(List<ChapterIndex> chapters, Consumer<ChapterIndex> onChapterSelected) {
        this.onChapterSelected = onChapterSelected;
        tocListView.getItems().setAll(chapters);
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) tocListView.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}