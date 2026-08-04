package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportWorkspaceController {

    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final ImportFileUseCase importFileUseCase;
    private final UiBackgroundExecutor executor;
    private final FileChooserService fileChooserService;
    private final DialogService dialogService;
    private final ApplicationState appState;
    private final SyncSeriesUseCase syncSeriesUseCase; // ЗАМІСТЬ SqliteSeriesRepository

    @Value("${app.import.batch-size:500}")
    private int defaultBatchSize;

    @FXML private TextField directoryField;
    @FXML private TextField fileField;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label foundFilesLabel;
    @FXML private Label importedBooksLabel;
    @FXML private Label errorsLabel;
    @FXML private Button cancelButton;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private volatile boolean importRunning = false;

    @FXML
    public void initialize() {
        if (cancelButton != null) {
            cancelButton.setDisable(true);
        }
        updateStats(0, 0, 0);
    }

    @FXML
    private void onChooseDirectory() {
        Stage stage = new Stage();
        File dir = fileChooserService.chooseDirectory(stage, "Виберіть папку з книгами");
        if (dir != null) {
            directoryField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onChooseFile() {
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, "Виберіть файл для імпорту",
                List.of(
                        new FileChooser.ExtensionFilter("Всі підтримувані", "*.fb2", "*.fbd", "*.inpx", "*.inp", "*.zip", "*.fb2zip", "*.fb2.zip"),
                        new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd"),
                        new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp"),
                        new FileChooser.ExtensionFilter("ZIP архіви", "*.zip", "*.fb2zip", "*.fb2.zip")
                ));
        if (file != null) {
            fileField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    private void onImportDirectory() {
        String path = directoryField.getText();
        if (path == null || path.isBlank()) {
            dialogService.showError("Помилка", "Виберіть папку для імпорту");
            return;
        }
        Path dir = Paths.get(path);
        if (!dir.toFile().exists() || !dir.toFile().isDirectory()) {
            dialogService.showError("Помилка", "Папка не існує");
            return;
        }
        startImport(() -> importDirectoryUseCase.execute(createContext(dir)));
    }

    @FXML
    private void onImportFile() {
        String path = fileField.getText();
        if (path == null || path.isBlank()) {
            dialogService.showError("Помилка", "Виберіть файл для імпорту");
            return;
        }
        Path file = Paths.get(path);
        if (!file.toFile().exists()) {
            dialogService.showError("Помилка", "Файл не існує");
            return;
        }
        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .file(file)
                    .batchSize(defaultBatchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .progressListener(this::updateProgress)
                    .statusConsumer(this::updateStatus)
                    .build();
            return importFileUseCase.execute(context);
        });
    }

    private ImportContext createContext(Path directory) {
        return ImportContext.builder()
                .rootDirectory(directory)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .cancelFlag(cancelFlag)
                .progressListener(this::updateProgress)
                .statusConsumer(this::updateStatus)
                .build();
    }

    private void startImport(java.util.concurrent.Callable<ImportResult> task) {
        if (importRunning) {
            dialogService.showWarning("Увага", "Імпорт вже виконується", "Зачекайте завершення поточного імпорту");
            return;
        }
        importRunning = true;
        cancelFlag.set(false);
        if (cancelButton != null) {
            cancelButton.setDisable(false);
        }
        updateStats(0, 0, 0);
        setStatus("Імпорт розпочато...");
        setProgress(0);

        executor.submit(task)
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    importRunning = false;
                    if (cancelButton != null) {
                        cancelButton.setDisable(true);
                    }
                    setStatus("Імпорт завершено. Додано " + result.imported() + " книг");
                    updateStats(result.imported(), result.errors(), 0);
                    appState.getStatusBar().setStatusText("Імпорт завершено: +" + result.imported() + " книг");

                    // Синхронізація series після імпорту через Use Case
                    try {
                        syncSeriesUseCase.execute();
                        log.info("Серії синхронізовано після імпорту");
                    } catch (Exception e) {
                        log.error("Помилка синхронізації серій після імпорту", e);
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        importRunning = false;
                        if (cancelButton != null) {
                            cancelButton.setDisable(true);
                        }
                        if (cancelFlag.get()) {
                            setStatus("Імпорт скасовано");
                        } else {
                            setStatus("Помилка імпорту: " + ex.getMessage());
                            dialogService.showError("Помилка", "Не вдалося виконати імпорт: " + ex.getMessage());
                        }
                    });
                    log.error("Import failed", ex);
                    return null;
                });
    }

    @FXML
    private void onCancel() {
        if (importRunning) {
            cancelFlag.set(true);
            if (cancelButton != null) {
                cancelButton.setDisable(true);
            }
            setStatus("Скасування...");
        }
    }

    @FXML
    private void onSettings() {
        dialogService.showInfo("Налаштування", "Налаштування імпорту", "Функція поки що не реалізована");
    }

    private void setStatus(String text) {
        UiExecutor.runOnUiThread(() -> statusLabel.setText(text));
    }

    private void updateStatus(String text) {
        UiExecutor.runOnUiThread(() -> statusLabel.setText(text));
    }

    private void setProgress(double value) {
        UiExecutor.runOnUiThread(() -> {
            progressBar.setProgress(value);
            progressLabel.setText(String.format("%.0f%%", value * 100));
        });
    }

    private void updateProgress(double value) {
        setProgress(value);
    }

    private void updateStats(long imported, long errors, long found) {
        UiExecutor.runOnUiThread(() -> {
            importedBooksLabel.setText(String.valueOf(imported));
            errorsLabel.setText(String.valueOf(errors));
            foundFilesLabel.setText(String.valueOf(found));
        });
    }
}