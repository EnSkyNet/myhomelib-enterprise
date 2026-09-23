package com.myhomelibcorp.ui.collection;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.folderwatch.IncomingFolderWatchState;
import com.myhomelibcorp.application.usecase.collection.IncomingFolderWatchUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Collection-workspace settings panel for MHL-113 incoming-folder automation. */
@Component
@RequiredArgsConstructor
final class IncomingFolderPanelCoordinator {
    private final IncomingFolderWatchUseCase useCase;
    private final DialogService dialogs;

    private TextField folderField;
    private CheckBox enabled;
    private Label status;
    private Button scanButton;
    private Node owner;
    private final AtomicLong requestGeneration = new AtomicLong();
    private String displayedCollectionId;

    void attach(TextField folderField, CheckBox enabled, Label status, Button scanButton, Node owner) {
        this.folderField = folderField;
        this.enabled = enabled;
        this.status = status;
        this.scanButton = scanButton;
        this.owner = owner;
    }

    void show(CollectionDto collection) {
        displayedCollectionId = collection == null ? null : collection.getId();
        requestGeneration.incrementAndGet();
        setBusy(false, null);
        if (collection == null || folderField == null) { clear(); return; }
        Optional<IncomingFolderWatchState> state = useCase.load(collection.getId());
        if (state.isEmpty()) { clear(); return; }
        render(state.get());
    }

    void browse(CollectionDto collection) {
        if (collection == null) {
            dialogs.showWarning(UiText.text("ui.java.incoming_folder_panel_coordinator.888ac617da"), UiText.text("ui.java.incoming_folder_panel_coordinator.6686ed3c16"));
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(UiText.text("ui.java.incoming_folder_panel_coordinator.d29516146a"));
        configureInitialDirectory(chooser);
        var window = owner == null || owner.getScene() == null ? null : owner.getScene().getWindow();
        java.io.File selected = chooser.showDialog(window);
        if (selected != null) folderField.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    void save(CollectionDto collection) {
        if (collection == null) return;
        String text = folderField == null ? "" : folderField.getText();
        if (text == null || text.isBlank()) {
            dialogs.showWarning(UiText.text("ui.java.incoming_folder_panel_coordinator.888ac617da"), UiText.text("ui.java.incoming_folder_panel_coordinator.0f24e73fd6"));
            return;
        }
        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true, UiText.text("ui.java.incoming_folder_panel_coordinator.5ae7fbfc3c"));
        useCase.configure(collectionId, Paths.get(text), enabled.isSelected())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(collectionId, requestId)) return;
                    setBusy(false, null);
                    if (error != null) dialogs.showError(UiText.text("ui.java.incoming_folder_panel_coordinator.888ac617da"), UiExceptionSupport.message(error));
                    else render(state);
                }));
    }

    void scanNow(CollectionDto collection) {
        if (collection == null) return;
        if (useCase.load(collection.getId()).isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.incoming_folder_panel_coordinator.888ac617da"), UiText.text("ui.java.incoming_folder_panel_coordinator.86aeea2e69"));
            return;
        }
        String collectionId = collection.getId();
        long requestId = requestGeneration.incrementAndGet();
        setBusy(true, UiText.text("ui.java.incoming_folder_panel_coordinator.9dca00a234"));
        useCase.scanNow(collectionId).whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrent(collectionId, requestId)) return;
            setBusy(false, null);
            if (error != null) dialogs.showError(UiText.text("ui.java.incoming_folder_panel_coordinator.888ac617da"), UiExceptionSupport.message(error));
            else render(state);
        }));
    }

    private void render(IncomingFolderWatchState state) {
        if (folderField != null) folderField.setText(state.folder() == null ? "" : state.folder().toString());
        if (enabled != null) enabled.setSelected(state.enabled());
        if (status != null) {
            status.setText(UiText.text("ui.java.incoming_folder_panel_coordinator.384f693f20") + displayStatus(state.status())
                    + UiText.text("ui.java.incoming_folder_panel_coordinator.fff39b1f26") + state.pendingCount()
                    + UiText.text("ui.java.incoming_folder_panel_coordinator.f785931bb7") + state.importedCount()
                    + UiText.text("ui.java.incoming_folder_panel_coordinator.88cf698e59") + state.duplicateContentCount()
                    + UiText.text("ui.java.incoming_folder_panel_coordinator.7d8819bf88") + state.failedCount());
        }
    }

    private void clear() {
        if (folderField != null) folderField.clear();
        if (enabled != null) enabled.setSelected(false);
        if (status != null) status.setText(UiText.text("ui.fxml.collection_workspace.incoming_folder_is_not_configured"));
    }

    private void configureInitialDirectory(DirectoryChooser chooser) {
        if (folderField == null || folderField.getText().isBlank()) return;
        try {
            Path current = Paths.get(folderField.getText()).toAbsolutePath().normalize();
            if (Files.isDirectory(current)) chooser.setInitialDirectory(current.toFile());
        } catch (RuntimeException ignored) { }
    }

    private void setBusy(boolean busy, String text) {
        if (scanButton != null) scanButton.setDisable(busy);
        if (status != null && text != null) status.setText(text);
    }

    private boolean isCurrent(String collectionId, long requestId) {
        return requestId == requestGeneration.get() && Objects.equals(displayedCollectionId, collectionId);
    }

    private static String displayStatus(String value) {
        if (value == null || value.isBlank()) return UiText.text("ui.java.incoming_folder_panel_coordinator.5f3c207c48");
        if (value.startsWith("SCAN_SCHEDULED:")) return UiText.text("ui.java.incoming_folder_panel_coordinator.0ec60d2cdd");
        if (value.startsWith("SCAN_ERROR:")) return UiText.text("ui.java.incoming_folder_panel_coordinator.e9de940c13");
        if (value.startsWith("WATCH_ERROR:")) return UiText.text("ui.java.incoming_folder_panel_coordinator.d074ae6bf9");
        return switch (value) {
            case "CONFIGURED" -> UiText.text("ui.java.incoming_folder_panel_coordinator.ae80e0563d");
            case "WATCHING" -> UiText.text("ui.java.incoming_folder_panel_coordinator.35041dba4a");
            case "FOLDER_MISSING" -> UiText.text("ui.java.incoming_folder_panel_coordinator.dc3e4d51cb");
            default -> value;
        };
    }
}
