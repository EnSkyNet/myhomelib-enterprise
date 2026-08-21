package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.service.ReaderSearchService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
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
public class SearchDialogController {

    @FXML
    private TextField searchField;

    @FXML
    private Button searchButton;

    @FXML
    private ListView<ReaderSearchService.SearchResult> resultsListView;

    @FXML
    private Label statusLabel;

    private ReaderDocument currentDocument;
    private Consumer<ReaderPosition> onResultSelected;

    @FXML
    public void initialize() {
        searchField.setOnAction(e -> performSearch());
        searchButton.setOnAction(e -> performSearch());

        resultsListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ReaderSearchService.SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String context = item.context();
                    if (context != null && context.length() > 80) {
                        context = context.substring(0, 80) + "...";
                    }
                    setText(context);
                }
            }
        });

        resultsListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ReaderSearchService.SearchResult selected = resultsListView.getSelectionModel().getSelectedItem();
                if (selected != null && onResultSelected != null) {
                    ReaderPosition pos = new ReaderPosition(
                            currentDocument.chapterIndexAt(selected.textOffset()),
                            selected.textOffset(),
                            selected.paragraphIndex(),
                            0
                    );
                    onResultSelected.accept(pos);
                    closeDialog();
                }
            }
        });
    }

    public void setDocument(ReaderDocument document, Consumer<ReaderPosition> onResultSelected) {
        this.currentDocument = document;
        this.onResultSelected = onResultSelected;
        searchField.requestFocus();
    }

    @FXML
    private void performSearch() {
        if (currentDocument == null) {
            return;
        }

        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            statusLabel.setText("Введіть текст для пошуку");
            resultsListView.getItems().clear();
            return;
        }

        ReaderSearchService searchService = new ReaderSearchService();
        List<ReaderSearchService.SearchResult> results = searchService.search(currentDocument, query);

        if (results.isEmpty()) {
            statusLabel.setText("Нічого не знайдено");
            resultsListView.getItems().clear();
        } else {
            statusLabel.setText("Знайдено " + results.size() + " збігів");
            resultsListView.getItems().setAll(results);
        }
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) searchField.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}