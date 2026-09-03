package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.usecase.collection.*;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.operation.OperationCenterEntry;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiExecutor;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionWorkspaceController implements WorkspaceLifecycle {

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final CollectionSourcePanelCoordinator sourcePanel;
    private final CollectionMaintenancePanelCoordinator maintenancePanel;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;

    @FXML private ListView<CollectionDto> collectionsListView;
    @FXML private Label collectionNameLabel;
    @FXML private Label activeCollectionLabel;
    @FXML private Label rootFolderLabel;
    @FXML private Label dbFileLabel;
    @FXML private Label collectionTypeLabel;
    @FXML private Label runtimeStateLabel;
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
    private final AtomicLong loadGeneration = new AtomicLong();
    private boolean busy;
    private volatile List<OperationCenterEntry> operationSnapshot = List.of();
    private AutoCloseable operationSubscription;

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
                    CollectionRuntimeStatus runtime = CollectionRuntimeStateResolver.resolve(item.getId(), operationSnapshot);
                    String prefix = item.isActive() ? "★ " : "";
                    setText(prefix + item.getName() + " — " + runtime.shortText());
                    getStyleClass().remove("accent-text");
                    if (item.isActive()) getStyleClass().add("accent-text");
                    setStyle("");
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

        subscribeToOperationLifecycle();
        loadCollections();
    }

    private void subscribeToOperationLifecycle() {
        closeOperationSubscription();
        operationSubscription = operationCenter.addListener(snapshot -> UiExecutor.runOnUiThread(() -> {
            operationSnapshot = snapshot == null ? List.of() : snapshot;
            if (collectionsListView != null) collectionsListView.refresh();
            if (selectedCollection != null) updateCollectionDetails(selectedCollection);
        }));
    }

    private void closeOperationSubscription() {
        AutoCloseable subscription = operationSubscription;
        operationSubscription = null;
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception e) {
            log.debug("Не вдалося закрити Operation Center subscription", e);
        }
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
        if (busy) return;

        log.info("Активація колекції: {}", collectionDto.getName());
        setBusy(true, "Активація колекції: " + collectionDto.getName());
        executor.submit(() -> switchCollectionUseCase.execute(collectionDto.getId()))
                .thenAccept(collection -> UiExecutor.runOnUiThread(() -> {
                    appState.setCurrentLibraryCollection(collection);
                    setBusy(false, "Активовано колекцію: " + collectionDto.getName());
                    loadCollections(collection.getId());
                    dialogService.showInfo("Успішно", "Колекцію \"" + collectionDto.getName() + "\" активовано.");
                }))
                .exceptionally(error -> {
                    log.error("Помилка активації колекції", error);
                    UiExecutor.runOnUiThread(() -> {
                        setBusy(false, "Помилка активації колекції");
                        dialogService.showError("Помилка", "Не вдалося активувати колекцію: " + ThrowableMessages.rootMessage(error));
                    });
                    return null;
                });
    }

    public void loadCollections() {
        loadCollections(null);
    }

    private void loadCollections(String selectId) {
        long requestId = loadGeneration.incrementAndGet();
        executor.submit(loadCollectionsUseCase::execute)
                .thenAccept(collections -> UiExecutor.runOnUiThread(() -> {
                    if (requestId != loadGeneration.get()) return;
                    applyCollections(collections, selectId);
                }))
                .exceptionally(error -> {
                    log.error("Помилка завантаження колекцій", error);
                    UiExecutor.runOnUiThread(() -> {
                        if (requestId != loadGeneration.get()) return;
                        dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + ThrowableMessages.rootMessage(error));
                    });
                    return null;
                });
    }

    private void applyCollections(List<CollectionDto> collections, String selectId) {
        collectionList.setAll(collections);
        activeCollection = collections.stream().filter(CollectionDto::isActive).findFirst().orElse(null);
        log.info("Завантажено {} колекцій; active={}", collections.size(),
                activeCollection == null ? "<none>" : activeCollection.getName());
        if (collections.isEmpty()) {
            selectedCollection = null;
            collectionDetailsBox.setVisible(false);
            return;
        }
        CollectionDto toSelect = null;
        if (selectId != null) {
            toSelect = collections.stream().filter(c -> selectId.equals(c.getId())).findFirst().orElse(null);
        }
        if (toSelect == null) toSelect = activeCollection != null ? activeCollection : collections.getFirst();
        collectionsListView.getSelectionModel().select(toSelect);
        updateActivateButton(toSelect);
    }

    private void updateCollectionDetails(CollectionDto collection) {
        if (collection == null) return;
        CollectionRuntimeStatus runtime = CollectionRuntimeStateResolver.resolve(collection.getId(), operationSnapshot);
        if (collectionNameLabel != null) collectionNameLabel.setText(collection.getName());
        if (runtimeStateLabel != null) runtimeStateLabel.setText("Стан: " + runtime.detailsText());
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
            if (name.isBlank() || busy) return;
            CreateCollectionRequest request = CreateCollectionRequest.builder()
                    .name(name)
                    .importOnCreate(false)
                    .createIndex(false)
                    .build();
            String operationId = operationCenter.start(
                    "Створення колекції — " + name, "", OperationKind.COLLECTION_CREATE,
                    OperationStage.CREATING_COLLECTION, false);
            setBusy(true, "Створення колекції: " + name);
            executor.submit(() -> {
                var created = createCollectionUseCase.execute(request);
                operationCenter.accept("Створення колекції — " + name, created.getId(), OperationKind.COLLECTION_CREATE,
                        OperationProgress.stage(operationId, OperationStage.FINALIZING, false));
                return switchCollectionUseCase.execute(created.getId());
            }).thenAccept(active -> UiExecutor.runOnUiThread(() -> {
                operationCenter.complete(operationId, "Колекція готова: " + active.getName());
                appState.setCurrentLibraryCollection(active);
                setBusy(false, "Колекцію створено: " + name);
                loadCollections(active.getId());
                dialogService.showInfo("Успішно", "Порожню колекцію \"" + name + "\" створено та активовано.");
                log.info("Порожню колекцію створено й активовано: id={}, name={}", active.getId(), active.getName());
            })).exceptionally(error -> {
                operationCenter.fail(operationId, error);
                log.error("Помилка створення колекції", error);
                UiExecutor.runOnUiThread(() -> {
                    setBusy(false, "Помилка створення колекції");
                    dialogService.showError("Помилка", "Не вдалося створити колекцію: " + ThrowableMessages.rootMessage(error));
                });
                return null;
            });
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
                if (busy) return;
                setBusy(true, "Перейменування колекції…");
                executor.submit(() -> renameCollectionUseCase.execute(selected.getId(), newName))
                        .thenAccept(renamed -> UiExecutor.runOnUiThread(() -> {
                            if (selected.isActive()) appState.setCurrentLibraryCollection(renamed);
                            setBusy(false, "Колекцію перейменовано");
                            loadCollections(renamed.getId());
                            dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                        }))
                        .exceptionally(error -> {
                            log.error("Помилка перейменування колекції", error);
                            UiExecutor.runOnUiThread(() -> {
                                setBusy(false, "Помилка перейменування колекції");
                                dialogService.showError("Помилка", "Не вдалося перейменувати: " + ThrowableMessages.rootMessage(error));
                            });
                            return null;
                        });
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
            if (busy) return;
            String operationId = operationCenter.start(
                    "Видалення колекції — " + selected.getName(), selected.getId(), OperationKind.COLLECTION_DELETE,
                    OperationStage.DELETING_COLLECTION, false);
            setBusy(true, "Видалення колекції: " + selected.getName());
            executor.submit(() -> {
                deleteCollectionUseCase.execute(selected.getId());
                return selected.getId();
            }).thenAccept(deletedId -> UiExecutor.runOnUiThread(() -> {
                operationCenter.complete(operationId, "Колекцію видалено");
                setBusy(false, "Колекцію видалено");
                loadCollections();
                dialogService.showInfo("Успішно", "Колекцію видалено");
            })).exceptionally(error -> {
                operationCenter.fail(operationId, error);
                log.error("Помилка видалення колекції", error);
                UiExecutor.runOnUiThread(() -> {
                    setBusy(false, "Помилка видалення колекції");
                    dialogService.showError("Помилка", "Не вдалося видалити: " + ThrowableMessages.rootMessage(error));
                });
                return null;
            });
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

    private void setBusy(boolean busy, String status) {
        this.busy = busy;
        collectionsListView.setDisable(busy);
        if (activateButton != null) activateButton.setDisable(busy || selectedCollection == null || selectedCollection.isActive());
        if (renameButton != null) renameButton.setDisable(busy || selectedCollection == null || !selectedCollection.isAllowRename());
        if (deleteButton != null) deleteButton.setDisable(busy || selectedCollection == null || !selectedCollection.isAllowDelete());
        appState.getStatusBar().setProgressVisible(busy);
        if (status != null && !status.isBlank()) appState.getStatusBar().setStatusText(status);
    }


    public void refresh() {
        loadCollections();
    }

    @Override
    public void dispose() {
        UiAsyncRequestGuard.invalidate(loadGeneration);
        closeOperationSubscription();
    }
}
