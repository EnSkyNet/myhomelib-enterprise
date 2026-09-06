package com.myhomelibcorp.ui.collection;

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
import java.util.Optional;

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

    void attach(TextField sourceFileField, CheckBox enabledCheckBox, Label statusLabel,
                Button checkButton, Node ownerNode) {
        this.sourceFileField = sourceFileField;
        this.enabledCheckBox = enabledCheckBox;
        this.statusLabel = statusLabel;
        this.checkButton = checkButton;
        this.ownerNode = ownerNode;
    }

    void show(CollectionDto collection) {
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
            dialogService.showWarning("Колекція", "Спочатку виберіть колекцію.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Виберіть локальний INPX/ZIP для автооновлення");
        chooser.getExtensionFilters().addAll(
                ImportFileChooserFilters.catalogAndZipSources(localizationService),
                new FileChooser.ExtensionFilter("Усі файли", "*.*"));
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
            dialogService.showWarning("Автооновлення", "Вкажіть локальний INPX/ZIP source-файл.");
            return;
        }

        setBusy(true);
        autoUpdateUseCase.configure(collection.getId(), Paths.get(source), enabledCheckBox.isSelected())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false);
                    if (error != null) {
                        dialogService.showError("Автооновлення", UiExceptionSupport.message(error));
                    } else {
                        render(state);
                        appState.getStatusBar().setStatusText("Налаштування автооновлення збережено");
                    }
                }));
    }

    void checkNow(CollectionDto collection) {
        if (collection == null) return;
        if (autoUpdateUseCase.load(collection.getId()).isEmpty()) {
            dialogService.showWarning("Автооновлення", "Спочатку збережіть source-файл.");
            return;
        }

        setBusy(true);
        autoUpdateUseCase.checkNow(collection.getId())
                .whenComplete((state, error) -> UiExecutor.runOnUiThread(() -> {
                    setBusy(false);
                    if (error != null) dialogService.showError("Перевірка джерела", UiExceptionSupport.message(error));
                    else render(state);
                }));
    }

    private void clear() {
        if (sourceFileField != null) sourceFileField.clear();
        if (enabledCheckBox != null) enabledCheckBox.setSelected(false);
        if (statusLabel != null) statusLabel.setText("Джерело автооновлення не налаштовано");
    }

    private void render(CollectionSourceState state) {
        if (sourceFileField != null) {
            sourceFileField.setText(state.sourceFile() == null ? "" : state.sourceFile().toString());
        }
        if (enabledCheckBox != null) enabledCheckBox.setSelected(state.enabled());
        if (statusLabel != null) {
            String prefix = state.updateAvailable() ? "⚠ Доступне оновлення" : "✓ Без нових змін";
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
        if (statusLabel != null && busy) statusLabel.setText("Перевірка source-файлу...");
    }

    private static String displayStatus(String status) {
        if (status == null) return "стан невідомий";
        return switch (status) {
            case "READY" -> "джерело доступне";
            case "APPLIED" -> "оновлення застосовано";
            case "SOURCE_MISSING" -> "файл джерела не знайдено";
            case "SOURCE_NOT_READABLE" -> "файл джерела недоступний для читання";
            case "SOURCE_DIRECTORY_MISSING" -> "каталог джерела не існує";
            default -> status.startsWith("SOURCE_ARCHIVE_INVALID") ? "пошкоджений INPX/ZIP" : status;
        };
    }


}
