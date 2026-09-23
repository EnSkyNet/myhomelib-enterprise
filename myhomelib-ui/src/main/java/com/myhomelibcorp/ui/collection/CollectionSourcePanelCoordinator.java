package com.myhomelibcorp.ui.collection;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.application.collection.CollectionSourceState;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.usecase.collection.CollectionAutoUpdateUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.imports.ImportFileChooserFilters;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the local INPX/ZIP source-monitor panel behavior for Collection Workspace. */
@Component
@RequiredArgsConstructor
final class CollectionSourcePanelCoordinator {
    private final LocalizationService localizationService;
    private final CollectionAutoUpdateUseCase autoUpdateUseCase;
    private final ApplicationState appState;
    private final DialogService dialogService;

    private TextField sourceFileField;
    private CheckBox enabledCheckBox;
    private Label statusLabel;
    private Button checkButton;
    private Node ownerNode;
    private final AtomicLong requestGeneration = new AtomicLong();
    private String displayedCollectionId;

    void attach(TextField sourceFileField, CheckBox enabledCheckBox, Label statusLabel,
                Button checkButton, Node ownerNode) {
        this.sourceFileField = sourceFileField;
        this.enabledCheckBox = enabledCheckBox;
        this.statusLabel = statusLabel;
        this.checkButton = checkButton;
        this.ownerNode = ownerNode;
    }

    void show(CollectionDto collection) {
        displayedCollectionId = collection == null ? null : collection.getId();
        requestGeneration.incrementAndGet();
        setBusy(false);
        if (sourceFileField == null || collection == null) {
            clear();
            return;
        }
        Optional<CollectionSourceState> state = autoUpdateUseCase.load(collection.getId());
        if (state.isEmpty()) {
            clear();
            return;
        }
        render(state.get());
    }

    void browse(CollectionDto collection) {
        if (collection == null) {
            dialogService.showWarning(UiText.text("ui.fxml.collection_workspace.collection"), UiText.text("ui.java.collection_source_panel_coordinator.6686ed3c16"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiText.text("ui.java.collection_source_panel_coordinator.7e07fe6985"));
        chooser.getExtensionFilters().addAll(
                ImportFileChooserFilters.catalogAndZipSources(localizationService),
                new FileChooser.ExtensionFilter(UiText.text("ui.java.collection_source_panel_coordinator.3efc61c5fd"), "*.*"));
        configureInitialDirectory(chooser);

        var window = ownerNode == null || ownerNode.getScene() == null ? null : ownerNode.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(window);
        if (selected != null && sourceFileField != null) {
            sourceFileField.setText(selected.toPath().toAbsolutePath().normalize().toString());
        }
    }

    void save(CollectionDto collection) {
        if (collection == null) return;
        String source = sourceFileField == null ? "" : sourceFileField.getText();
        if (source == null || source.isBlank()) {
            dialogService.showWarning(UiText.text("ui.java.collection_source_panel_coordinator.88645354c9"), UiText.text("ui.java.collection_source_panel_coordinator.379e9b588f"));
            return;
        }

        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true);
        autoUpdateUseCase.configure(collectionId, Paths.get(source), enabledCheckBox.isSelected())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false);
                    if (error != null) {
                        dialogService.showError(UiText.text("ui.java.collection_source_panel_coordinator.88645354c9"), UiExceptionSupport.message(error));
                    } else {
                        render(state);
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_source_panel_coordinator.d7f644f993"));
                    }
                }));
    }

    void checkNow(CollectionDto collection) {
        if (collection == null) return;
        if (autoUpdateUseCase.load(collection.getId()).isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.collection_source_panel_coordinator.88645354c9"), UiText.text("ui.java.collection_source_panel_coordinator.ccf21b3750"));
            return;
        }

        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true);
        autoUpdateUseCase.checkNow(collectionId)
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false);
                    if (error != null) dialogService.showError(UiText.text("ui.java.collection_source_panel_coordinator.69a2d0969a"), UiExceptionSupport.message(error));
                    else render(state);
                }));
    }

    private void clear() {
        if (sourceFileField != null) sourceFileField.clear();
        if (enabledCheckBox != null) enabledCheckBox.setSelected(false);
        if (statusLabel != null) statusLabel.setText(UiText.text("ui.fxml.collection_workspace.auto_update_source_is_not_configured"));
    }

    private void render(CollectionSourceState state) {
        if (sourceFileField != null) {
            sourceFileField.setText(state.sourceFile() == null ? "" : state.sourceFile().toString());
        }
        if (enabledCheckBox != null) enabledCheckBox.setSelected(state.enabled());
        if (statusLabel != null) {
            String prefix = state.updateAvailable() ? UiText.text("ui.java.collection_source_panel_coordinator.7093fa4e86") : UiText.text("ui.java.collection_source_panel_coordinator.80183792e4");
            statusLabel.setText(prefix + " · " + displayStatus(state.status()));
        }
    }

    private void configureInitialDirectory(FileChooser chooser) {
        if (sourceFileField == null || sourceFileField.getText().isBlank()) return;
        try {
            Path current = Paths.get(sourceFileField.getText()).toAbsolutePath().normalize();
            if (current.getParent() != null && Files.isDirectory(current.getParent())) {
                chooser.setInitialDirectory(current.getParent().toFile());
            }
        } catch (RuntimeException ignored) {
            // Invalid/incomplete text in the field must not prevent opening the chooser.
        }
    }

    private void setBusy(boolean busy) {
        if (checkButton != null) checkButton.setDisable(busy);
        if (statusLabel != null && busy) statusLabel.setText(UiText.text("ui.java.collection_source_panel_coordinator.f3cf68c364"));
    }

    private boolean isCurrent(String collectionId, long requestId) {
        return requestId == requestGeneration.get() && Objects.equals(displayedCollectionId, collectionId);
    }

    private static String displayStatus(String status) {
        if (status == null) return UiText.text("ui.java.collection_source_panel_coordinator.f6a66ef515");
        return switch (status) {
            case "READY" -> UiText.text("ui.java.collection_source_panel_coordinator.56b5ffea34");
            case "APPLIED" -> UiText.text("ui.java.collection_source_panel_coordinator.670eea73e1");
            case "SOURCE_MISSING" -> UiText.text("ui.java.collection_source_panel_coordinator.2589da9149");
            case "SOURCE_NOT_READABLE" -> UiText.text("ui.java.collection_source_panel_coordinator.bb7ada9f29");
            case "SOURCE_DIRECTORY_MISSING" -> UiText.text("ui.java.collection_source_panel_coordinator.864af386fe");
            default -> status.startsWith("SOURCE_ARCHIVE_INVALID") ? UiText.text("ui.java.collection_source_panel_coordinator.92ca06bda4") : status;
        };
    }


}
