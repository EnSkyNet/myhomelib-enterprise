package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportWorkspaceController {

    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final UiBackgroundExecutor executor;
    private final FileChooserService fileChooserService;
    private final DialogService dialogService;
    private final ApplicationState appState;

    @FXML private TextField pathField;
    @FXML private Label statusLabel;
    @FXML private Label foundBooksLabel;
    @FXML private Label newBooksLabel;
    @FXML private Label duplicatesLabel;
    @FXML private Label errorsLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label currentFileLabel;
    @FXML private Button selectButton;
    @FXML private Button startButton;
    @FXML private Button pauseButton;
    @FXML private Button cancelButton;

    private AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private AtomicBoolean pausedFlag = new AtomicBoolean(false);
    private volatile boolean isRunning = false;

    @FXML
    public void initialize() {
        progressBar.setProgress(0);
        updateButtons(false);
        statusLabel.setText("Готово до імпорту");
    }

    @FXML
    private void onSelectDirectory() {
        Stage stage = new Stage();
        File dir = fileChooserService.chooseDirectory(stage, "Виберіть каталог з книгами");
        if (dir != null) {
            pathField.setText(dir.getAbsolutePath());
            // Спроба підрахувати кількість файлів
            // Для реальної роботи потрібно викликати LibraryScanner
            foundBooksLabel.setText("Знайдено книг: ...");
        }
    }

    @FXML
    private void onSelectFile() {
        Stage stage = new Stage();
        List<FileChooser.ExtensionFilter> filters = List.of(
                new FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd"),
                new FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp"),
                new FileChooser.ExtensionFilter("Всі файли", "*.*")
        );
        File file = fileChooserService.chooseFile(stage, "Виберіть файл для імпорту", filters);
        if (file != null) {
            pathField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    private void onStartImport() {
        String path = pathField.getText();
        if (path == null || path.isBlank()) {
            dialogService.showError("Помилка", "Виберіть каталог або файл");
            return;
        }

        Path selectedPath = Path.of(path);
        if (selectedPath.toFile().isDirectory()) {
            startDirectoryImport(selectedPath);
        } else if (selectedPath.toFile().isFile()) {
            startFileImport(selectedPath);
        } else {
            dialogService.showError("Помилка", "Шлях не існує");
        }
    }

    private void startDirectoryImport(Path directory) {
        isRunning = true;
        cancelFlag.set(false);
        pausedFlag.set(false);
        updateButtons(true);
        statusLabel.setText("Імпорт каталогу...");
        progressBar.setProgress(0);

        ImportContext context = ImportContext.builder()
                .rootDirectory(directory)
                .batchSize(500)
                .indexAfterSave(true)
                .progressListener(progress -> UiExecutor.runOnUiThread(() -> progressBar.setProgress(progress)))
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusLabel.setText(status)))
                .cancelFlag(cancelFlag)
                .build();

        executor.submit(() -> importDirectoryUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    isRunning = false;
                    updateButtons(false);
                    showResults(result);
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        isRunning = false;
                        updateButtons(false);
                        statusLabel.setText("Помилка: " + ex.getMessage());
                        dialogService.showError("Помилка імпорту", ex.getMessage());
                    });
                    log.error("Import failed", ex);
                    return null;
                });
    }

    private void startFileImport(Path file) {
        isRunning = true;
        cancelFlag.set(false);
        pausedFlag.set(false);
        updateButtons(true);
        statusLabel.setText("Імпорт файлу...");
        progressBar.setProgress(0);

        ImportContext context = ImportContext.builder()
                .file(file)
                .batchSize(500)
                .indexAfterSave(true)
                .progressListener(progress -> UiExecutor.runOnUiThread(() -> progressBar.setProgress(progress)))
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusLabel.setText(status)))
                .cancelFlag(cancelFlag)
                .build();

        executor.submit(() -> importFileUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    isRunning = false;
                    updateButtons(false);
                    showResults(result);
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        isRunning = false;
                        updateButtons(false);
                        statusLabel.setText("Помилка: " + ex.getMessage());
                        dialogService.showError("Помилка імпорту", ex.getMessage());
                    });
                    log.error("Import failed", ex);
                    return null;
                });
    }

    private void showResults(ImportResult result) {
        foundBooksLabel.setText("Знайдено книг: " + (result.imported() + result.skipped() + result.duplicates()));
        newBooksLabel.setText("Нових: " + result.imported());
        duplicatesLabel.setText("Дублікатів: " + result.duplicates());
        errorsLabel.setText("Помилок: " + result.errors());
        statusLabel.setText("Імпорт завершено за " + result.durationMs() + " мс");
        progressBar.setProgress(1.0);
    }

    @FXML
    private void onPause() {
        if (isRunning) {
            pausedFlag.set(!pausedFlag.get());
            pauseButton.setText(pausedFlag.get() ? "Продовжити" : "Пауза");
            statusLabel.setText(pausedFlag.get() ? "На паузі" : "Продовжено");
        }
    }

    @FXML
    private void onCancel() {
        if (isRunning) {
            cancelFlag.set(true);
            statusLabel.setText("Скасування...");
            updateButtons(false);
        }
    }

    private void updateButtons(boolean running) {
        startButton.setDisable(running);
        selectButton.setDisable(running);
        pauseButton.setDisable(!running);
        cancelButton.setDisable(!running);
        if (!running) {
            pauseButton.setText("Пауза");
        }
    }

    @FXML
    private void onClear() {
        pathField.clear();
        foundBooksLabel.setText("Знайдено книг: 0");
        newBooksLabel.setText("Нових: 0");
        duplicatesLabel.setText("Дублікатів: 0");
        errorsLabel.setText("Помилок: 0");
        progressBar.setProgress(0);
        statusLabel.setText("Готово до імпорту");
        currentFileLabel.setText("");
    }
}