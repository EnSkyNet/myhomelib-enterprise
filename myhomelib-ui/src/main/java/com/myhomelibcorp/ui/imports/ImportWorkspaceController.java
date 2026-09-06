package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
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
import java.util.UUID;
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
    private final OperationCenterService operationCenter;
    private final FileChooserService fileChooserService;
    private final DialogService dialogService;
    private final ApplicationState appState;
    private final LocalizationService i18n;

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
        File dir = fileChooserService.chooseDirectory(stage, i18n.text("ui.import.choose_directory.title"));
        if (dir != null) {
            directoryField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onChooseFile() {
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, i18n.text("ui.import.choose_file.title"), ImportFileChooserFilters.standardGroups(i18n));
        if (file != null) {
            fileField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    private void onImportDirectory() {
        String path = directoryField.getText();
        if (path == null || path.isBlank()) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.import.error.choose_directory"));
            return;
        }
        Path dir = Paths.get(path);
        if (!dir.toFile().exists() || !dir.toFile().isDirectory()) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.import.error.directory_missing"));
            return;
        }

        // Не робимо Files.walk() у JavaFX thread лише заради попереднього count.
        // Для великих локальних/NAS бібліотек діалог має з'явитися одразу; authoritative
        // X/Y надходить через OperationProgress, а до появи total показуємо indeterminate state.
        progressDialog = new ImportProgressDialog(i18n, i18n.text("ui.import.progress.catalog_title"));
        progressDialog.setIndeterminate(true);
        progressDialog.setOnCancel(() -> {
            cancelFlag.set(true);
            progressDialog.updateStatus(i18n.text("ui.import.status.cancelling"));
        });
        progressDialog.show();

        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .rootDirectory(dir)
                    .updateExisting(true)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .operationId("directory-import-" + UUID.randomUUID())
                    .progressListener(this::updateProgress)
                    .operationProgressListener(this::updateOperationProgress)
                    .statusConsumer(this::updateStatus)
                    .build();
            return importDirectoryUseCase.execute(context);
        });
    }

    @FXML
    private void onImportFile() {
        String path = fileField.getText();
        if (path == null || path.isBlank()) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.import.error.choose_file"));
            return;
        }
        Path file = Paths.get(path);
        if (!file.toFile().exists()) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.import.error.file_missing"));
            return;
        }

        progressDialog = new ImportProgressDialog(i18n, i18n.text("ui.import.progress.file_title"));
        progressDialog.setIndeterminate(true);
        progressDialog.setOnCancel(() -> {
            cancelFlag.set(true);
            progressDialog.updateStatus(i18n.text("ui.import.status.cancelling"));
        });
        progressDialog.show();

        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .file(file)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .operationId("file-import-" + UUID.randomUUID())
                    .progressListener(this::updateProgress)
                    .operationProgressListener(this::updateOperationProgress)
                    .statusConsumer(this::updateStatus)
                    .build();
            return importFileUseCase.execute(context);
        });
    }

    private void startImport(java.util.concurrent.Callable<ImportResult> task) {
        if (importRunning) {
            dialogService.showWarning(i18n.text("common.warning"), i18n.text("ui.import.warning.already_running"), i18n.text("ui.import.warning.wait_current"));
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
        setStatus(i18n.text("ui.import.status.started"));
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
                            progressDialog.updateStatus(i18n.text("ui.import.status.cancelled"));
                            progressDialog.updateProgress(result.imported(), result.imported() + result.skipped() + result.duplicates() + result.errors(), i18n.text("ui.import.status.cancelled_short"));
                        } else {
                            progressDialog.updateProgress(result.imported(), result.imported() + result.skipped() + result.duplicates() + result.errors(), i18n.text("ui.import.status.completed"));
                        }

                        closeProgressDialogAfter(Duration.seconds(1));
                    }

                    if (cancelFlag.get()) {
                        setStatus(i18n.text("ui.import.status.cancelled_rollback"));
                        setProgress(0);
                    } else {
                        long total = result.imported() + result.skipped() + result.duplicates() + result.errors();
                        updateStats(result.imported(), result.errors(), total);
                        setProgress(1.0);
                        String summary = formatImportSummary(result, total);
                        setStatus(summary);
                        appState.getStatusBar().setStatusText(
                                i18n.format("ui.import.status.completed_summary",
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
                            progressDialog.updateStatus(i18n.format("common.error.with_message", ex.getMessage()));
                            closeProgressDialogAfter(Duration.seconds(1.5));
                        }

                        if (cancelFlag.get()) {
                            setStatus(i18n.text("ui.import.status.cancelled"));
                        } else {
                            setStatus(i18n.format("ui.import.status.error", ex.getMessage()));
                            dialogService.showError(i18n.text("common.error"), i18n.format("ui.import.error.failed", ex.getMessage()));
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
            setStatus(i18n.text("ui.import.status.cancelling"));

            if (progressDialog != null) {
                progressDialog.cancel();
                progressDialog.updateStatus(i18n.text("ui.import.status.cancelling"));
            }
        }
    }

    @FXML
    private void onSettings() {
        TextInputDialog dialog = new TextInputDialog(String.valueOf(batchSize));
        dialog.setTitle(i18n.text("ui.import.settings.title"));
        dialog.setHeaderText(i18n.text("ui.import.settings.batch_header"));
        dialog.setContentText(i18n.text("ui.import.settings.batch_label"));
        dialog.showAndWait().ifPresent(value -> {
            try {
                int parsed = clampBatchSize(Integer.parseInt(value.trim()));
                batchSize = parsed;
                preferences.putInt("import.batchSize", parsed);
                dialogService.showInfo(i18n.text("ui.import.settings.title"), i18n.text("common.saved"), i18n.format("ui.import.settings.batch_saved", parsed));
            } catch (NumberFormatException e) {
                dialogService.showError(i18n.text("common.error"), i18n.text("ui.import.settings.batch_invalid"));
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
        // Legacy scalar progress remains useful for the compact status bar, but the modal dialog
        // is rendered from OperationProgress so we never invent fake processed/total values.
        setProgress(value);
        if (progressDialog != null && value < 0) progressDialog.setIndeterminate(true);
    }

    private void updateOperationProgress(OperationProgress progress) {
        if (progress == null) return;
        var collection = appState.getCurrentLibraryCollection();
        operationCenter.accept(i18n.text("ui.import.operation.title"), collection == null ? "" : collection.getId(), progress);
        double fraction = progress.fraction();
        setProgress(fraction);
        ImportProgressDialog dialog = progressDialog;
        if (dialog != null) dialog.update(progress);
        long imported = Math.max(0L, progress.inserted() + progress.updated());
        long found = progress.total() >= 0 ? progress.total() : progress.processed();
        updateStats(imported, progress.errors(), Math.max(0L, found));
    }

    private String formatImportSummary(ImportResult result, long total) {
        return String.format(
                i18n.text("ui.import.summary.full"),
                total,
                result.imported(),
                result.changes().insertedCount(),
                result.changes().updatedCount(),
                result.explicitlyDeleted(),
                result.withoutAuthor(),
                result.withoutGenre(),
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
    private void closeProgressDialogAfter(Duration delay) {
        ImportProgressDialog dialog = progressDialog;
        if (dialog == null) return;
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(event -> {
            dialog.close();
            if (progressDialog == dialog) progressDialog = null;
        });
        pause.play();
    }

}