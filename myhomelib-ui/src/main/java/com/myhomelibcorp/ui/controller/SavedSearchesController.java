package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class SavedSearchesController {

    private final LoadSavedSearchesUseCase loadSavedSearchesUseCase;
    private final SaveSearchUseCase saveSearchUseCase;
    private final DeleteSavedSearchUseCase deleteSavedSearchUseCase;
    private final DialogService dialogService;

    @FXML private ListView<SavedSearch> savedSearchesListView;
    @FXML private TextField searchNameField;
    @FXML private TextField searchQueryField;

    private Consumer<String> onSearchSelected;

    @FXML
    public void initialize() {
        savedSearchesListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(SavedSearch item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + " (" + item.getUseCount() + ")");
                }
            }
        });

        // Подвійний клік для завантаження пошуку
        savedSearchesListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                SavedSearch selected = savedSearchesListView.getSelectionModel().getSelectedItem();
                if (selected != null && onSearchSelected != null) {
                    onSearchSelected.accept(selected.getQuery());
                }
            }
        });

        // Контекстне меню
        ContextMenu contextMenu = new ContextMenu();
        MenuItem loadItem = new MenuItem("🔍 Завантажити");
        loadItem.setOnAction(e -> {
            SavedSearch selected = savedSearchesListView.getSelectionModel().getSelectedItem();
            if (selected != null && onSearchSelected != null) {
                onSearchSelected.accept(selected.getQuery());
            }
        });

        MenuItem deleteItem = new MenuItem("🗑 Видалити");
        deleteItem.setOnAction(e -> {
            SavedSearch selected = savedSearchesListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteSavedSearch(selected);
            }
        });

        contextMenu.getItems().addAll(loadItem, deleteItem);
        savedSearchesListView.setContextMenu(contextMenu);

        loadSearches();
    }

    public void setOnSearchSelected(Consumer<String> onSearchSelected) {
        this.onSearchSelected = onSearchSelected;
    }

    @FXML
    private void onSaveSearch() {
        String name = searchNameField.getText();
        String query = searchQueryField.getText();

        if (name == null || name.isBlank()) {
            dialogService.showWarning("Увага", "Введіть назву пошуку");
            return;
        }
        if (query == null || query.isBlank()) {
            dialogService.showWarning("Увага", "Введіть запит для збереження");
            return;
        }

        try {
            saveSearchUseCase.execute(name, query, null);
            searchNameField.clear();
            searchQueryField.clear();
            loadSearches();
            dialogService.showInfo("Успішно", "Пошук '" + name + "' збережено");
        } catch (Exception e) {
            log.error("Помилка збереження пошуку", e);
            dialogService.showError("Помилка", "Не вдалося зберегти пошук: " + e.getMessage());
        }
    }

    private void deleteSavedSearch(SavedSearch search) {
        if (dialogService.showConfirmation("Підтвердження",
                "Видалити пошук '" + search.getName() + "'?",
                "Цю дію не можна скасувати.")) {
            try {
                deleteSavedSearchUseCase.execute(search.getId());
                loadSearches();
                dialogService.showInfo("Успішно", "Пошук видалено");
            } catch (Exception e) {
                log.error("Помилка видалення пошуку", e);
                dialogService.showError("Помилка", "Не вдалося видалити пошук: " + e.getMessage());
            }
        }
    }

    public void loadSearches() {
        try {
            var searches = loadSavedSearchesUseCase.execute();
            UiExecutor.runOnUiThread(() -> {
                savedSearchesListView.getItems().setAll(searches);
                if (searches.isEmpty()) {
                    savedSearchesListView.setPlaceholder(new javafx.scene.control.Label("Немає збережених пошуків"));
                }
            });
        } catch (Exception e) {
            log.error("Помилка завантаження збережених пошуків", e);
        }
    }

    @FXML
    private void onClose() {
        Stage stage = (Stage) savedSearchesListView.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}