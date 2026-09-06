package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.service.ReaderSearchService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

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

    private final UiBackgroundExecutor backgroundExecutor;
    private final LocalizationService i18n;
    private ReaderDocument currentDocument;
    private Consumer<ReaderPosition> onResultSelected;
    private final AtomicLong searchGeneration = new AtomicLong();
    private volatile Future<?> searchTask;

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
        if (currentDocument == null) return;

        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            statusLabel.setText(i18n.text("ui.reader.search.enter_text"));
            resultsListView.getItems().clear();
            return;
        }

        long generation = searchGeneration.incrementAndGet();
        cancelSearch();
        searchButton.setDisable(true);
        statusLabel.setText(i18n.text("ui.reader.search.searching"));
        ReaderDocument document = currentDocument;
        try {
            searchTask = backgroundExecutor.submitCancellable(() -> {
                try {
                    List<ReaderSearchService.SearchResult> results = new ReaderSearchService().search(document, query);
                    javafx.application.Platform.runLater(() -> applyResults(generation, results, null));
                } catch (Throwable error) {
                    javafx.application.Platform.runLater(() -> applyResults(generation, List.of(), error));
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            searchButton.setDisable(false);
            statusLabel.setText(i18n.text("ui.reader.search.queue_busy"));
        }
    }

    private void applyResults(long generation, List<ReaderSearchService.SearchResult> results, Throwable error) {
        if (generation != searchGeneration.get()) return;
        searchButton.setDisable(false);
        if (error != null) {
            if (error instanceof java.util.concurrent.CancellationException) return;
            statusLabel.setText(error.getMessage() == null ? i18n.text("ui.reader.search.error") : error.getMessage());
            resultsListView.getItems().clear();
            return;
        }
        if (results.isEmpty()) {
            statusLabel.setText(i18n.text("ui.reader.search.nothing_found"));
            resultsListView.getItems().clear();
        } else {
            statusLabel.setText(i18n.format("ui.reader.search.matches_found", results.size()));
            resultsListView.getItems().setAll(results);
        }
    }

    private void cancelSearch() {
        Future<?> task = searchTask;
        searchTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        searchGeneration.incrementAndGet();
        cancelSearch();
        Stage stage = (Stage) searchField.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}