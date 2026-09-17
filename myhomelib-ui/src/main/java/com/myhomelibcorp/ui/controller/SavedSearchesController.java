package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.MarkSavedSearchUsedUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.application.usecase.search.SetSavedSearchPinnedUseCase;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.stage.Modality;
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
    private final SetSavedSearchPinnedUseCase setPinnedUseCase;
    private final MarkSavedSearchUsedUseCase markUsedUseCase;
    private final DialogService dialogService;
    private final FxmlLoaderFactory fxmlLoaderFactory;
    private final LocalizationService i18n;

    @FXML private ListView<SavedSearch> savedSearchesListView;
    @FXML private TextField searchNameField;
    @FXML private TextField searchQueryField;

    private Consumer<SavedSearch> onSearchSelected;

    @FXML
    public void initialize() {
        savedSearchesListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SavedSearch item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String kind = item.isSmartCollection() ? " ◈" : "";
                    setText((item.isPinned() ? "★ " : "") + item.getName() + kind + " (" + item.getUseCount() + ")");
                }
            }
        });

        savedSearchesListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) activateSelected();
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem loadItem = new MenuItem(i18n.text("ui.saved_search.load"));
        loadItem.setOnAction(e -> activateSelected());
        MenuItem pinItem = new MenuItem(i18n.text("ui.saved_search.pin_toggle"));
        pinItem.setOnAction(e -> togglePinned());
        MenuItem editItem = new MenuItem(i18n.text("ui.smart_collection.edit"));
        editItem.setOnAction(e -> {
            SavedSearch selected = selected();
            if (selected != null && selected.isSmartCollection()) openSmartCollectionDialog(selected);
        });
        MenuItem deleteItem = new MenuItem(i18n.text("common.delete"));
        deleteItem.setOnAction(e -> {
            SavedSearch selected = selected();
            if (selected != null) deleteSavedSearch(selected);
        });
        contextMenu.setOnShowing(e -> {
            SavedSearch selected = selected();
            editItem.setDisable(selected == null || !selected.isSmartCollection());
        });
        contextMenu.getItems().addAll(loadItem, pinItem, editItem, new SeparatorMenuItem(), deleteItem);
        savedSearchesListView.setContextMenu(contextMenu);

        loadSearches();
    }

    public void setOnSearchSelected(Consumer<SavedSearch> onSearchSelected) {
        this.onSearchSelected = onSearchSelected;
    }

    @FXML
    private void onSaveSearch() {
        String name = searchNameField.getText();
        String query = searchQueryField.getText();
        if (name == null || name.isBlank()) {
            dialogService.showWarning(i18n.text("common.warning"), i18n.text("ui.saved_search.name_required"));
            return;
        }
        if (query == null || query.isBlank()) {
            dialogService.showWarning(i18n.text("common.warning"), i18n.text("ui.saved_search.query_required"));
            return;
        }
        try {
            saveSearchUseCase.execute(name, query, null);
            searchNameField.clear();
            searchQueryField.clear();
            loadSearches();
            dialogService.showInfo(i18n.text("common.success"), i18n.format("ui.saved_search.saved", name));
        } catch (Exception e) {
            log.error("Cannot save search", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.saved_search.save_error", e.getMessage()));
        }
    }

    @FXML
    private void onNewSmartCollection() {
        openSmartCollectionDialog(null);
    }

    private void openSmartCollectionDialog(SavedSearch editing) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/smart-collection.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);
            SmartCollectionDialogController controller = loader.getController();
            controller.setOnSaved(this::loadSearches);
            if (editing != null) controller.edit(editing.getId());
            Stage stage = new Stage();
            stage.setTitle(i18n.text(editing == null ? "ui.smart_collection.new" : "ui.smart_collection.edit"));
            stage.setScene(new Scene(root));
            stage.initModality(Modality.WINDOW_MODAL);
            if (savedSearchesListView.getScene() != null) stage.initOwner(savedSearchesListView.getScene().getWindow());
            stage.show();
        } catch (Exception e) {
            log.error("Cannot open smart collection dialog", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.smart_collection.open_error", e.getMessage()));
        }
    }

    private void activateSelected() {
        SavedSearch selected = selected();
        if (selected == null || onSearchSelected == null) return;
        try {
            SavedSearch used = markUsedUseCase.execute(selected.getId());
            onSearchSelected.accept(used);
            loadSearches();
        } catch (Exception e) {
            log.error("Cannot activate saved search", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.saved_search.load_error", e.getMessage()));
        }
    }

    private void togglePinned() {
        SavedSearch selected = selected();
        if (selected == null) return;
        try {
            setPinnedUseCase.execute(selected.getId(), !selected.isPinned());
            loadSearches();
        } catch (Exception e) {
            log.error("Cannot change saved-search pin", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.saved_search.pin_error", e.getMessage()));
        }
    }

    private SavedSearch selected() {
        return savedSearchesListView.getSelectionModel().getSelectedItem();
    }

    private void deleteSavedSearch(SavedSearch search) {
        if (dialogService.showConfirmation(i18n.text("common.confirmation"),
                i18n.format("ui.saved_search.delete_confirm", search.getName()),
                i18n.text("ui.saved_search.delete_irreversible"))) {
            try {
                deleteSavedSearchUseCase.execute(search.getId());
                loadSearches();
            } catch (Exception e) {
                log.error("Cannot delete saved search", e);
                dialogService.showError(i18n.text("common.error"), i18n.format("ui.saved_search.delete_error", e.getMessage()));
            }
        }
    }

    public void loadSearches() {
        try {
            var searches = loadSavedSearchesUseCase.execute();
            UiExecutor.runOnUiThread(() -> {
                savedSearchesListView.getItems().setAll(searches);
                if (searches.isEmpty()) {
                    savedSearchesListView.setPlaceholder(new Label(i18n.text("ui.saved_search.empty")));
                }
            });
        } catch (Exception e) {
            log.error("Cannot load saved searches", e);
        }
    }

    @FXML
    private void onClose() {
        Stage stage = (Stage) savedSearchesListView.getScene().getWindow();
        if (stage != null) stage.close();
    }
}
