package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.mapper.CollectionDtoMapper;
import com.myhomelibcorp.application.usecase.collection.*;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionWorkspaceController {

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final CollectionSourcePanelCoordinator sourcePanel;
    private final CollectionMaintenancePanelCoordinator maintenancePanel;
    private final ApplicationState appState;
    private final DialogService dialogService;

    @FXML private ListView<CollectionDto> collectionsListView;
    @FXML private Label collectionNameLabel;
    @FXML private Label activeCollectionLabel;
    @FXML private Label rootFolderLabel;
    @FXML private Label dbFileLabel;
    @FXML private Label collectionTypeLabel;
    @FXML private Button renameButton;
    @FXML private Button deleteButton;
    @FXML private Button activateButton;
    @FXML private VBox collectionDetailsBox;
    @FXML private TextField sourceFileField;
    @FXML private CheckBox autoUpdateEnabledCheckBox;
    @FXML private Label sourceStatusLabel;
    @FXML private Button sourceCheckButton;
    @FXML private Button maintenanceAnalyzeButton;
    @FXML private Button maintenanceDryRunButton;
    @FXML private Button maintenanceApplyButton;
    @FXML private Label maintenanceStatusLabel;
    @FXML private TextArea maintenanceReportArea;

    private CollectionDto selectedCollection;
    private CollectionDto activeCollection;
    private final ObservableList<CollectionDto> collectionList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        sourcePanel.attach(sourceFileField, autoUpdateEnabledCheckBox, sourceStatusLabel, sourceCheckButton, collectionsListView);
        maintenancePanel.attach(maintenanceAnalyzeButton, maintenanceDryRunButton, maintenanceApplyButton,
                maintenanceStatusLabel, maintenanceReportArea);

        collectionsListView.setItems(collectionList);
        collectionsListView.setCellFactory(lv -> new ListCell<CollectionDto>() {
            @Override
            protected void updateItem(CollectionDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    String prefix = item.isActive() ? "● " : "○ ";
                    setText(prefix + item.getName());
                    if (item.isActive()) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #2196F3;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // ===== КОНТЕКСТНЕ МЕНЮ =====
        ContextMenu contextMenu = new ContextMenu();

        // Пункт "Активувати"
        MenuItem activateItem = new MenuItem("▶ Активувати");
        activateItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isActive()) {
                activateCollection(selected);
            }
        });

        // Пункт "Перейменувати"
        MenuItem renameItem = new MenuItem("✏️ Перейменувати");
        renameItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isAllowRename()) {
                onRenameCollection();
            }
        });

        // Пункт "Видалити"
        MenuItem deleteItem = new MenuItem("🗑 Видалити");
        deleteItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isAllowDelete() && !selected.isActive()) {
                onDeleteCollection();
            }
        });

        // Пункт "Оновити"
        MenuItem refreshItem = new MenuItem("🔄 Оновити");
        refreshItem.setOnAction(e -> loadCollections());

        // Пункт "Копіювати ID"
        MenuItem copyIdItem = new MenuItem("📋 Копіювати ID");
        copyIdItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(selected.getId());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                dialogService.showInfo("Копійовано", "ID колекції скопійовано в буфер обміну.");
            }
        });

        // Роздільник
        SeparatorMenuItem separator = new SeparatorMenuItem();

        contextMenu.getItems().addAll(
                activateItem,
                renameItem,
                deleteItem,
                separator,
                refreshItem,
                copyIdItem
        );
        collectionsListView.setContextMenu(contextMenu);

        // ===== ОНОВЛЕННЯ КОНТЕКСТНОГО МЕНЮ ПРИ ВИБОРІ =====
        collectionsListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                // Оновлюємо стан пунктів меню
                activateItem.setDisable(selected.isActive());
                renameItem.setDisable(!selected.isAllowRename());
                deleteItem.setDisable(!selected.isAllowDelete() || selected.isActive());

                selectedCollection = selected;
                updateCollectionDetails(selected);
                sourcePanel.show(selected);
                maintenancePanel.show(selected);
                collectionDetailsBox.setVisible(true);
                updateActivateButton(selected);
                log.info("Вибрано колекцію: {} (active={})", selected.getName(), selected.isActive());
            } else {
                selectedCollection = null;
                collectionDetailsBox.setVisible(false);
            }
        });

        // Подвійний клік для активації
        collectionsListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
                if (selected != null && !selected.isActive()) {
                    activateCollection(selected);
                }
            }
        });

        loadCollections();
    }

    public ObservableList<CollectionDto> getCollectionList() {
        return collectionList;
    }

    private void updateActivateButton(CollectionDto collection) {
        if (activateButton != null) {
            if (collection == null || collection.isActive()) {
                activateButton.setDisable(true);
                activateButton.setText("✅ Активна");
            } else {
                activateButton.setDisable(false);
                activateButton.setText("▶ Активувати");
            }
        }
    }

    @FXML
    private void onActivateCollection() {
        if (selectedCollection != null && !selectedCollection.isActive()) {
            activateCollection(selectedCollection);
        }
    }

    private void activateCollection(CollectionDto collectionDto) {
        if (collectionDto == null) {
            dialogService.showWarning("Активація", "Виберіть колекцію для активації.");
            return;
        }

        if (collectionDto.isActive()) {
            dialogService.showInfo("Інформація", "Колекція \"" + collectionDto.getName() + "\" вже активна.");
            return;
        }

        log.info("Активація колекції: {}", collectionDto.getName());
        appState.getStatusBar().setStatusText("Активація колекції: " + collectionDto.getName());
        appState.getStatusBar().setProgressVisible(true);

        try {
            // Переключаємося за ID. Use case сам завантажує повний metadata-запис,
            // включно з URL/login/password/notes, яких немає у CollectionDto.
            Collection collection = switchCollectionUseCase.execute(collectionDto.getId());

            // Оновлюємо стан
            appState.setCurrentLibraryCollection(collection);

            // Оновлюємо список
            loadCollections();

            // Вибираємо активовану колекцію в списку
            for (CollectionDto dto : collectionList) {
                if (dto.getId().equals(collectionDto.getId())) {
                    collectionsListView.getSelectionModel().select(dto);
                    break;
                }
            }

            appState.getStatusBar().setStatusText("Активовано колекцію: " + collectionDto.getName());
            appState.getStatusBar().setProgressVisible(false);

            dialogService.showInfo("Успішно", "Колекцію \"" + collectionDto.getName() + "\" активовано.");

        } catch (Exception e) {
            log.error("Помилка активації колекції", e);
            appState.getStatusBar().setProgressVisible(false);
            appState.getStatusBar().setStatusText("Помилка активації колекції");
            dialogService.showError("Помилка", "Не вдалося активувати колекцію: " + e.getMessage());
        }
    }

    public void loadCollections() {
        try {
            List<CollectionDto> collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
            activeCollection = collections.stream().filter(CollectionDto::isActive).findFirst().orElse(null);
            log.info("Завантажено {} колекцій; active={}", collections.size(),
                    activeCollection == null ? "<none>" : activeCollection.getName());
            if (!collections.isEmpty()) {
                CollectionDto toSelect = activeCollection != null ? activeCollection : collections.getFirst();
                collectionsListView.getSelectionModel().select(toSelect);
                updateActivateButton(toSelect);
            } else {
                collectionDetailsBox.setVisible(false);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    private void updateCollectionDetails(CollectionDto collection) {
        if (collection == null) return;
        if (activeCollectionLabel != null) {
            activeCollectionLabel.setText(collection.isActive() ? "Активна колекція: так" : "Активна колекція: ні");
        }
        if (rootFolderLabel != null) {
            rootFolderLabel.setText("Папка: " + displayValue(collection.getRootFolder()));
        }
        if (dbFileLabel != null) {
            dbFileLabel.setText("БД: " + displayValue(collection.getDbFile()));
        }
        if (collectionTypeLabel != null) {
            collectionTypeLabel.setText("Тип: " + com.myhomelibcorp.domain.model.collection.CollectionType
                    .fromCode(collection.getType()).getDisplayName());
        }
        if (renameButton != null) renameButton.setDisable(!collection.isAllowRename());
        if (deleteButton != null) deleteButton.setDisable(!collection.isAllowDelete());
        updateActivateButton(collection);
    }

    private String displayValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    @FXML
    private void onCreateCollection() {
        Optional<String> result = dialogService.showTextInput(
                "Створити колекцію",
                "Введіть назву нової колекції",
                "Назва:",
                "");
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                try {
                    CreateCollectionRequest request = CreateCollectionRequest.builder()
                            .name(name)
                            .importOnCreate(false)
                            .createIndex(false)
                            .build();

                    com.myhomelibcorp.domain.model.collection.Collection created = createCollectionUseCase.execute(request);
                    com.myhomelibcorp.domain.model.collection.Collection active = switchCollectionUseCase.execute(created.getId());
                    appState.setCurrentLibraryCollection(active);
                    loadCollections();
                    collectionList.stream()
                            .filter(dto -> dto.getId().equals(active.getId()))
                            .findFirst()
                            .ifPresent(dto -> collectionsListView.getSelectionModel().select(dto));
                    dialogService.showInfo("Успішно", "Порожню колекцію \"" + name + "\" створено та активовано.");
                    log.info("Порожню колекцію створено й активовано: id={}, name={}", active.getId(), active.getName());
                } catch (Exception e) {
                    log.error("Помилка створення колекції", e);
                    dialogService.showError("Помилка", "Не вдалося створити колекцію: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onRenameCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowRename()) {
            dialogService.showError("Помилка", "Цю колекцію не можна перейменовувати");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменувати колекцію",
                "Введіть нову назву для \"" + selected.getName() + "\"",
                "Нова назва:",
                selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                try {
                    com.myhomelibcorp.domain.model.collection.Collection renamed =
                            renameCollectionUseCase.execute(selected.getId(), newName);
                    CollectionDto updated = CollectionDtoMapper.toDto(
                            renamed, selected.isActive(), selected.isAllowDelete());
                    int index = collectionList.indexOf(selected);
                    if (index >= 0) {
                        collectionList.set(index, updated);
                    }
                    if (selected.isActive()) {
                        appState.setCurrentLibraryCollection(renamed);
                    }
                    collectionsListView.getSelectionModel().select(updated);
                    dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                } catch (Exception e) {
                    log.error("Помилка перейменування колекції", e);
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void onDeleteCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError("Помилка", "Виберіть колекцію");
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError("Помилка", "Системну колекцію не можна видалити");
            return;
        }
        if (selected.isActive()) {
            dialogService.showWarning("Увага", "Неможливо видалити активну колекцію. Спочатку активуйте іншу.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + selected.getName() + "\"?");
        confirm.setContentText("Книги не будуть видалені, лише зв'язки.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteCollectionUseCase.execute(selected.getId());
                collectionList.remove(selected);
                collectionDetailsBox.setVisible(false);
                dialogService.showInfo("Успішно", "Колекцію видалено");
            } catch (Exception e) {
                log.error("Помилка видалення колекції", e);
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onBrowseSource() {
        sourcePanel.browse(selectedCollection);
    }

    @FXML
    private void onSaveSourceMonitor() {
        sourcePanel.save(selectedCollection);
    }

    @FXML
    private void onCheckSourceNow() {
        sourcePanel.checkNow(selectedCollection);
    }

    @FXML
    private void onAnalyzeMaintenance() {
        maintenancePanel.analyze(selectedCollection);
    }

    @FXML
    private void onDryRunMaintenance() {
        maintenancePanel.dryRun(selectedCollection);
    }

    @FXML
    private void onApplyMaintenance() {
        maintenancePanel.apply(selectedCollection);
    }

    @FXML
    private void onRefresh() {
        loadCollections();
        dialogService.showInfo("Оновлення", "Колекції перезавантажено.");
    }

    public void refresh() {
        loadCollections();
    }
}