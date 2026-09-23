package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
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
    private final IncomingFolderPanelCoordinator incomingFolderPanel;
    private final CollectionMaintenancePanelCoordinator maintenancePanel;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final CollectionUiCommitGuard collectionUiCommitGuard;

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
    @FXML private TextField incomingFolderField;
    @FXML private CheckBox incomingFolderEnabledCheckBox;
    @FXML private Label incomingFolderStatusLabel;
    @FXML private Button incomingFolderScanButton;
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
        incomingFolderPanel.attach(incomingFolderField, incomingFolderEnabledCheckBox, incomingFolderStatusLabel, incomingFolderScanButton, collectionsListView);
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

        // Пункт UiText.text("ui.java.collection_workspace_controller.6f12385e9a")
        MenuItem activateItem = new MenuItem(UiText.text("ui.fxml.collection_workspace.activate"));
        activateItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isActive()) {
                activateCollection(selected);
            }
        });

        // Пункт UiText.text("ui.java.collection_workspace_controller.b4b0846a77")
        MenuItem renameItem = new MenuItem(UiText.text("ui.java.collection_workspace_controller.15474d976a"));
        renameItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isAllowRename()) {
                onRenameCollection();
            }
        });

        // Пункт UiText.text("common.delete")
        MenuItem deleteItem = new MenuItem(UiText.text("ui.java.collection_workspace_controller.0d682b84b8"));
        deleteItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.isAllowDelete() && !selected.isActive()) {
                onDeleteCollection();
            }
        });

        // Пункт UiText.text("ui.annotations.refresh")
        MenuItem refreshItem = new MenuItem(UiText.text("ui.fxml.statistics.refresh"));
        refreshItem.setOnAction(e -> loadCollections());

        // Пункт UiText.text("ui.java.collection_workspace_controller.190be426f9")
        MenuItem copyIdItem = new MenuItem(UiText.text("ui.java.collection_workspace_controller.e20adf4990"));
        copyIdItem.setOnAction(e -> {
            CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(selected.getId());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                dialogService.showInfo(UiText.text("ui.java.collection_workspace_controller.87b1e3f42d"), UiText.text("ui.java.collection_workspace_controller.d5bce995d7"));
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
                incomingFolderPanel.show(selected);
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
                activateButton.setText(UiText.text("ui.java.collection_workspace_controller.1e3b975a65"));
            } else {
                activateButton.setDisable(false);
                activateButton.setText(UiText.text("ui.fxml.collection_workspace.activate"));
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
            dialogService.showWarning(UiText.text("ui.java.collection_workspace_controller.3f18914622"), UiText.text("ui.java.collection_workspace_controller.49d94639d0"));
            return;
        }
        if (collectionDto.isActive()) {
            dialogService.showInfo(UiText.text("ui.java.collection_workspace_controller.455cfc2d2f"), UiText.text("ui.java.collection_workspace_controller.f2d5f8e855") + collectionDto.getName() + UiText.text("ui.java.collection_workspace_controller.dc86a73057"));
            return;
        }
        if (busy) return;

        log.info("Активація колекції: {}", collectionDto.getName());
        setBusy(true, UiText.text("ui.java.collection_workspace_controller.fcc111b24a") + collectionDto.getName());
        executor.submit(() -> switchCollectionUseCase.execute(collectionDto.getId()))
                .thenAccept(collection -> UiExecutor.runOnUiThread(() -> {
                    boolean committed = collectionUiCommitGuard.runIfStillActive(collection.getId(),
                            () -> appState.setCurrentLibraryCollection(collection));
                    if (!committed) {
                        setBusy(false, UiText.text("ui.java.collection_workspace_controller.d4da4c002a"));
                        loadCollections();
                        return;
                    }
                    setBusy(false, UiText.text("ui.java.collection_workspace_controller.4d1606f89f") + collectionDto.getName());
                    loadCollections(collection.getId());
                    dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.collection_workspace_controller.3575cb9ad1") + collectionDto.getName() + UiText.text("ui.java.collection_workspace_controller.357606f014"));
                }))
                .exceptionally(error -> {
                    log.error("Помилка активації колекції", error);
                    UiExecutor.runOnUiThread(() -> {
                        setBusy(false, UiText.text("ui.java.collection_workspace_controller.d4da4c002a"));
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.ac50c37794") + UiExceptionSupport.message(error));
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
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.6462ec753f") + UiExceptionSupport.message(error));
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
        if (runtime.state() == CollectionRuntimeState.ERROR && runtime.detail() != null && !runtime.detail().isBlank()) {
            log.warn("Collection {} runtime technical detail: {}", collection.getId(), runtime.detail());
        }
        if (runtimeStateLabel != null) runtimeStateLabel.setText(UiText.text("ui.java.collection_workspace_controller.384f693f20") + runtime.detailsText());
        if (activeCollectionLabel != null) {
            activeCollectionLabel.setText(collection.isActive() ? UiText.text("ui.java.collection_workspace_controller.b01be21a1e") : UiText.text("ui.fxml.collection_workspace.active_collection_no"));
        }
        if (rootFolderLabel != null) {
            rootFolderLabel.setText(UiText.text("ui.java.collection_workspace_controller.5c7051dc85") + displayValue(collection.getRootFolder()));
        }
        if (dbFileLabel != null) {
            dbFileLabel.setText(UiText.text("ui.java.collection_workspace_controller.7e0da525ec") + displayValue(collection.getDbFile()));
        }
        if (collectionTypeLabel != null) {
            var type = com.myhomelibcorp.domain.model.collection.CollectionType.fromCode(collection.getType());
            collectionTypeLabel.setText(UiText.text("ui.java.collection_workspace_controller.9a32dc9dce")
                    + UiText.text(type.getLocalizationKey()));
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
                UiText.text("ui.java.collection_workspace_controller.365d3d5a7f"),
                UiText.text("ui.java.collection_workspace_controller.1dc64220a2"),
                UiText.text("common.name.label"),
                "");
        result.ifPresent(name -> {
            if (name.isBlank() || busy) return;
            CreateCollectionRequest request = CreateCollectionRequest.builder()
                    .name(name)
                    .importOnCreate(false)
                    .createIndex(false)
                    .build();
            String operationId = operationCenter.start(
                    UiText.text("ui.java.collection_workspace_controller.9dce38545e") + name, "", OperationKind.COLLECTION_CREATE,
                    OperationStage.CREATING_COLLECTION, false);
            setBusy(true, UiText.text("ui.java.collection_workspace_controller.6178107aa9") + name);
            executor.submit(() -> {
                var created = createCollectionUseCase.execute(request);
                operationCenter.accept(UiText.text("ui.java.collection_workspace_controller.9dce38545e") + name, created.getId(), OperationKind.COLLECTION_CREATE,
                        OperationProgress.stage(operationId, OperationStage.FINALIZING, false));
                return switchCollectionUseCase.execute(created.getId());
            }).thenAccept(active -> UiExecutor.runOnUiThread(() -> {
                operationCenter.complete(operationId, UiText.text("ui.java.collection_workspace_controller.1e198f406d") + active.getName());
                boolean committed = collectionUiCommitGuard.runIfStillActive(active.getId(),
                        () -> appState.setCurrentLibraryCollection(active));
                if (!committed) {
                    setBusy(false, UiText.text("ui.java.collection_workspace_controller.d4da4c002a"));
                    loadCollections();
                    return;
                }
                setBusy(false, UiText.text("ui.java.collection_workspace_controller.e3751d7234") + name);
                loadCollections(active.getId());
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.collection_workspace_controller.f0a78c800d") + name + UiText.text("ui.java.collection_workspace_controller.03cc09f511"));
                log.info("Порожню колекцію створено й активовано: id={}, name={}", active.getId(), active.getName());
            })).exceptionally(error -> {
                operationCenter.fail(operationId, error);
                log.error("Помилка створення колекції", error);
                UiExecutor.runOnUiThread(() -> {
                    setBusy(false, UiText.text("ui.java.collection_workspace_controller.ab1972de78"));
                    dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.b76f02b6d4") + UiExceptionSupport.message(error));
                });
                return null;
            });
        });
    }

    @FXML
    private void onRenameCollection() {
        CollectionDto selected = collectionsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.6ed32c10b7"));
            return;
        }
        if (!selected.isAllowRename()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.d5d3e15d5e"));
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                UiText.text("ui.fxml.collection_workspace.rename_collection"),
                UiText.text("ui.java.collection_workspace_controller.0ff4d98614") + selected.getName() + "\"",
                UiText.text("ui.java.collection_workspace_controller.db3f487087"),
                selected.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(selected.getName())) {
                if (busy) return;
                setBusy(true, UiText.text("ui.java.collection_workspace_controller.0ab48ee16c"));
                executor.submit(() -> renameCollectionUseCase.execute(selected.getId(), newName))
                        .thenAccept(renamed -> UiExecutor.runOnUiThread(() -> {
                            if (selected.isActive()) {
                                collectionUiCommitGuard.runIfStillActive(renamed.getId(),
                                        () -> appState.setCurrentLibraryCollection(renamed));
                            }
                            setBusy(false, UiText.text("ui.java.collection_workspace_controller.82308d6991"));
                            loadCollections(renamed.getId());
                            dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.collection_workspace_controller.1751051254") + newName + "\"");
                        }))
                        .exceptionally(error -> {
                            log.error("Помилка перейменування колекції", error);
                            UiExecutor.runOnUiThread(() -> {
                                setBusy(false, UiText.text("ui.java.collection_workspace_controller.2d4ee1600f"));
                                dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.d207d46772") + UiExceptionSupport.message(error));
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
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.6ed32c10b7"));
            return;
        }
        if (!selected.isAllowDelete()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.05f7e2f690"));
            return;
        }
        if (selected.isActive()) {
            dialogService.showWarning(UiText.text("common.warning"), UiText.text("ui.java.collection_workspace_controller.fc5a43f192"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(confirm);
        confirm.setTitle(UiText.text("common.confirmation"));
        confirm.setHeaderText(UiText.text("ui.java.collection_workspace_controller.2cc2b8d292") + selected.getName() + "\"?");
        confirm.setContentText(UiText.text("ui.java.collection_workspace_controller.c23e524406"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            if (busy) return;
            String operationId = operationCenter.start(
                    UiText.text("ui.java.collection_workspace_controller.a8fd607a8e") + selected.getName(), selected.getId(), OperationKind.COLLECTION_DELETE,
                    OperationStage.DELETING_COLLECTION, false);
            setBusy(true, UiText.text("ui.java.collection_workspace_controller.4e53396d9f") + selected.getName());
            executor.submit(() -> {
                deleteCollectionUseCase.execute(selected.getId());
                return selected.getId();
            }).thenAccept(deletedId -> UiExecutor.runOnUiThread(() -> {
                operationCenter.complete(operationId, UiText.text("ui.java.collection_workspace_controller.da28e22019"));
                setBusy(false, UiText.text("ui.java.collection_workspace_controller.da28e22019"));
                loadCollections();
                dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.collection_workspace_controller.da28e22019"));
            })).exceptionally(error -> {
                operationCenter.fail(operationId, error);
                log.error("Помилка видалення колекції", error);
                UiExecutor.runOnUiThread(() -> {
                    setBusy(false, UiText.text("ui.java.collection_workspace_controller.b10d7c9dec"));
                    dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_workspace_controller.7481af0840") + UiExceptionSupport.message(error));
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
    private void onBrowseIncomingFolder() { incomingFolderPanel.browse(selectedCollection); }

    @FXML
    private void onSaveIncomingFolder() { incomingFolderPanel.save(selectedCollection); }

    @FXML
    private void onScanIncomingFolder() { incomingFolderPanel.scanNow(selectedCollection); }

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
        dialogService.showInfo(UiText.text("ui.fxml.updates_workspace.updates"), UiText.text("ui.java.collection_workspace_controller.2a11af6f33"));
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
