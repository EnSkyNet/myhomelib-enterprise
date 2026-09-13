package com.myhomelibcorp.ui.collection;

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
import java.util.Optional;

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

    void attach(TextField folderField, CheckBox enabled, Label status, Button scanButton, Node owner) {
        this.folderField = folderField;
        this.enabled = enabled;
        this.status = status;
        this.scanButton = scanButton;
        this.owner = owner;
    }

    void show(CollectionDto collection) {
        if (collection == null || folderField == null) { clear(); return; }
        Optional<IncomingFolderWatchState> state = useCase.load(collection.getId());
        if (state.isEmpty()) { clear(); return; }
        render(state.get());
    }

    void browse(CollectionDto collection) {
        if (collection == null) {
            dialogs.showWarning("Incoming folder", "Спочатку виберіть колекцію.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку автоматичного імпорту");
        configureInitialDirectory(chooser);
        var window = owner == null || owner.getScene() == null ? null : owner.getScene().getWindow();
        java.io.File selected = chooser.showDialog(window);
        if (selected != null) folderField.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    void save(CollectionDto collection) {
        if (collection == null) return;
        String text = folderField == null ? "" : folderField.getText();
        if (text == null || text.isBlank()) {
            dialogs.showWarning("Incoming folder", "Вкажіть папку автоматичного імпорту.");
            return;
        }
        setBusy(true, "Збереження налаштувань...");
        useCase.configure(collection.getId(), Paths.get(text), enabled.isSelected())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false, null);
                    if (error != null) dialogs.showError("Incoming folder", UiExceptionSupport.message(error));
                    else render(state);
                }));
    }

    void scanNow(CollectionDto collection) {
        if (collection == null) return;
        if (useCase.load(collection.getId()).isEmpty()) {
            dialogs.showWarning("Incoming folder", "Спочатку збережіть налаштування папки.");
            return;
        }
        setBusy(true, "Сканування папки...");
        useCase.scanNow(collection.getId()).whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
            setBusy(false, null);
            if (error != null) dialogs.showError("Incoming folder", UiExceptionSupport.message(error));
            else render(state);
        }));
    }

    private void render(IncomingFolderWatchState state) {
        if (folderField != null) folderField.setText(state.folder() == null ? "" : state.folder().toString());
        if (enabled != null) enabled.setSelected(state.enabled());
        if (status != null) {
            status.setText("Стан: " + displayStatus(state.status())
                    + " · очікує/готово/імпортується: " + state.pendingCount()
                    + " · імпортовано: " + state.importedCount()
                    + " · дублікати за вмістом: " + state.duplicateContentCount()
                    + " · помилки: " + state.failedCount());
        }
    }

    private void clear() {
        if (folderField != null) folderField.clear();
        if (enabled != null) enabled.setSelected(false);
        if (status != null) status.setText("Incoming folder не налаштовано");
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

    private static String displayStatus(String value) {
        if (value == null || value.isBlank()) return "невідомо";
        if (value.startsWith("SCAN_SCHEDULED:")) return "файли поставлено на stability check";
        if (value.startsWith("SCAN_ERROR:")) return "помилка сканування";
        if (value.startsWith("WATCH_ERROR:")) return "помилка WatchService";
        return switch (value) {
            case "CONFIGURED" -> "налаштовано";
            case "WATCHING" -> "Watcher активний";
            case "FOLDER_MISSING" -> "папку не знайдено";
            default -> value;
        };
    }
}
