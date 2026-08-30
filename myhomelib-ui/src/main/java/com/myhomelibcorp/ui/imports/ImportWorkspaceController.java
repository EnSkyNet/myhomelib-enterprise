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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

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

    @Value("${app.import.batch-size:1000}")
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
    private final Preferences preferences = Preferences.userNodeForPackage(ImportWorkspaceController.class);
    private int batchSize;

    // Діалог прогресу
    private ImportProgressDialog progressDialog;

    @FXML
    public void initialize() {
        batchSize = clampBatchSize(preferences.getInt("import.batchSize", defaultBatchSize));
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
                        new FileChooser.ExtensionFilter("Всі підтримувані", "*.fb2", "*.fbd", "*.epub", "*.txt", "*.inpx", "*.inp", "*.zip", "*.fb2zip", "*.fb2.zip", "*.7z", "*.rar", "*.cbz"),
                        new FileChooser.ExtensionFilter("Книги", "*.fb2", "*.fbd", "*.epub", "*.txt"),
                        new FileChooser.ExtensionFilter("INPX/INP", "*.inpx", "*.inp"),
                        new FileChooser.ExtensionFilter("Архіви", "*.zip", "*.fb2zip", "*.fb2.zip", "*.7z", "*.rar", "*.cbz")
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

        // Отримуємо кількість файлів для прогресу
        long totalFiles = 0;
        try (var stream = java.nio.file.Files.walk(dir)) {
            totalFiles = stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return name.endsWith(".fb2") || name.endsWith(".fbd") || name.endsWith(".epub") ||
                                name.endsWith(".txt") || name.endsWith(".inpx") || name.endsWith(".inp") ||
                                name.endsWith(".zip") || name.endsWith(".fb2zip");
                    })
                    .count();
        } catch (Exception e) {
            log.warn("Не вдалося підрахувати файли: {}", e.getMessage());
        }

        progressDialog = new ImportProgressDialog("Імпорт каталогу");
        progressDialog.setTotal(totalFiles);
        progressDialog.setOnCancel(() -> {
            cancelFlag.set(true);
            progressDialog.updateStatus("Скасування...");
        });
        progressDialog.show();

        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .rootDirectory(dir)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .progressListener(this::updateProgress)
                    .statusConsumer(this::updateStatus)
                    .build();
            return importDirectoryUseCase.execute(context);
        });
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

        progressDialog = new ImportProgressDialog("Імпорт файлу");
        progressDialog.setTotal(1);
        progressDialog.setOnCancel(() -> {
            cancelFlag.set(true);
            progressDialog.updateStatus("Скасування...");
        });
        progressDialog.show();

        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .file(file)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .progressListener(this::updateProgress)
                    .statusConsumer(this::updateStatus)
                    .build();
            return importFileUseCase.execute(context);
        });
    }

    private void startImport(java.util.concurrent.Callable<ImportResult> task) {
        if (importRunning) {
            dialogService.showWarning("Увага", "Імпорт вже виконується", "Зачекайте завершення поточного імпорту");
            if (progressDialog != null) progressDialog.show();
            return;
        }
        importRunning = true;
        cancelFlag.set(false);
        if (cancelButton != null) {
            cancelButton.setDisable(false);
        }
        updateStats(0, 0, 0);
        appState.getStatusBar().setProgressVisible(true);
        setStatus("Імпорт розпочато...");
        setProgress(0);

        long startTime = System.currentTimeMillis();
        AtomicLong totalImported = new AtomicLong(0);

        executor.submit(task)
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    importRunning = false;
                    if (cancelButton != null) {
                        cancelButton.setDisable(true);
                    }
                    appState.getStatusBar().setProgressVisible(false);

                    if (progressDialog != null) {
                        if (cancelFlag.get()) {
                            progressDialog.updateStatus("Імпорт скасовано");
                            progressDialog.updateProgress(result.imported(), result.imported() + result.skipped() + result.duplicates() + result.errors(), "Скасовано");
                        } else {
                            progressDialog.updateProgress(result.imported(), result.imported() + result.skipped() + result.duplicates() + result.errors(), "Завершено!");
                        }

                        // Затримка перед закриттям, щоб користувач побачив результат
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        progressDialog.close();
                        progressDialog = null;
                    }

                    if (cancelFlag.get()) {
                        setStatus("Імпорт скасовано. Незавершені зміни не збережено.");
                        setProgress(0);
                    } else {
                        long total = result.imported() + result.skipped() + result.duplicates() + result.errors();
                        updateStats(result.imported(), result.errors(), total);
                        setProgress(1.0);
                        String summary = formatImportSummary(result, total);
                        setStatus(summary);
                        appState.getStatusBar().setStatusText(
                                String.format("Імпорт завершено: %,d книг, помилок %,d",
                                        result.imported(), result.errors()));
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        importRunning = false;
                        if (cancelButton != null) {
                            cancelButton.setDisable(true);
                        }
                        appState.getStatusBar().setProgressVisible(false);

                        if (progressDialog != null) {
                            progressDialog.updateStatus("Помилка: " + ex.getMessage());
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            progressDialog.close();
                            progressDialog = null;
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

            if (progressDialog != null) {
                progressDialog.cancel();
                progressDialog.updateStatus("Скасування...");
            }
        }
    }

    @FXML
    private void onSettings() {
        TextInputDialog dialog = new TextInputDialog(String.valueOf(batchSize));
        dialog.setTitle("Налаштування імпорту");
        dialog.setHeaderText("Розмір пакета імпорту");
        dialog.setContentText("Книг у пакеті (50–10000):");
        dialog.showAndWait().ifPresent(value -> {
            try {
                int parsed = clampBatchSize(Integer.parseInt(value.trim()));
                batchSize = parsed;
                preferences.putInt("import.batchSize", parsed);
                dialogService.showInfo("Налаштування", "Збережено", "Розмір пакета: " + parsed);
            } catch (NumberFormatException e) {
                dialogService.showError("Помилка", "Введіть ціле число від 50 до 10000");
            }
        });
    }

    private int clampBatchSize(int value) {
        return Math.max(50, Math.min(10_000, value));
    }

    private void setStatus(String text) {
        UiExecutor.runOnUiThread(() -> {
            statusLabel.setText(text);
            appState.getStatusBar().setStatusText(text == null ? "" : text.replace('\n', ' '));
        });
    }

    private void updateStatus(String text) {
        setStatus(text);
        if (progressDialog != null) {
            progressDialog.updateStatus(text);
        }
    }

    private void setProgress(double value) {
        UiExecutor.runOnUiThread(() -> {
            if (value < 0) {
                progressBar.setProgress(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS);
                progressLabel.setText("…");
                appState.getStatusBar().setProgress(-1);
                return;
            }
            double bounded = Math.max(0.0, Math.min(1.0, value));
            progressBar.setProgress(bounded);
            progressLabel.setText(String.format("%.0f%%", bounded * 100));
            appState.getStatusBar().setProgress(bounded);
        });
    }

    private void updateProgress(double value) {
        setProgress(value);
        if (progressDialog == null) return;
        if (value < 0) {
            progressDialog.setIndeterminate(true);
        } else {
            progressDialog.setIndeterminate(false);
            if (value > 0) {
                long processed = (long) (value * 1000);
                progressDialog.updateProgress(processed, 1000, null);
            }
        }
    }

    private String formatImportSummary(ImportResult result, long total) {
        long durationMs = System.currentTimeMillis() - 0; // треба передавати час
        return String.format(
                "Імпорт завершено%n%nЗаписів: %,d%nІмпортовано: %,d%nПропущено: %,d%nДублікатів: %,d%nПомилок: %,d%n%nЧас: %s",
                total,
                result.imported(),
                result.skipped(),
                result.duplicates(),
                result.errors(),
                formatDuration(result.durationMs()));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format("%02d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    private void updateStats(long imported, long errors, long found) {
        UiExecutor.runOnUiThread(() -> {
            importedBooksLabel.setText(String.valueOf(imported));
            errorsLabel.setText(String.valueOf(errors));
            foundFilesLabel.setText(String.valueOf(found));
        });
    }
}