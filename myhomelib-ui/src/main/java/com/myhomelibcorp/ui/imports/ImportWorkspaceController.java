package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
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

        String expectedCollectionId = currentCollectionId();
        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .rootDirectory(dir)
                    .expectedCollectionId(expectedCollectionId)
                    .updateExisting(true)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .operationId("directory-import-" + UUID.randomUUID())
                    .progressListener(value -> { if (isCurrentCollection(expectedCollectionId)) updateProgress(value); })
                    .operationProgressListener(progress -> updateOperationProgress(expectedCollectionId, progress))
                    .statusConsumer(text -> { if (isCurrentCollection(expectedCollectionId)) updateStatus(text); })
                    .build();
            return importDirectoryUseCase.execute(context);
        }, expectedCollectionId);
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

        String expectedCollectionId = currentCollectionId();
        startImport(() -> {
            ImportContext context = ImportContext.builder()
                    .file(file)
                    .expectedCollectionId(expectedCollectionId)
                    .batchSize(batchSize)
                    .indexAfterSave(true)
                    .cancelFlag(cancelFlag)
                    .operationId("file-import-" + UUID.randomUUID())
                    .progressListener(value -> { if (isCurrentCollection(expectedCollectionId)) updateProgress(value); })
                    .operationProgressListener(progress -> updateOperationProgress(expectedCollectionId, progress))
                    .statusConsumer(text -> { if (isCurrentCollection(expectedCollectionId)) updateStatus(text); })
                    .build();
            return importFileUseCase.execute(context);
        }, expectedCollectionId);
    }

    private void startImport(java.util.concurrent.Callable<ImportResult> task, String expectedCollectionId) {
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
                    if (!isCurrentCollection(expectedCollectionId)) {
                        if (progressDialog != null) progressDialog.close();
                        return;
                    }
                    appState.getStatusBar().setProgressVisible(false);

                    boolean cancelled = result.status() == ImportStatus.CANCELLED;
                    long total = result.imported() + result.skipped() + result.duplicates() + result.errors();
                    if (progressDialog != null) {
                        if (cancelled) {
                            // Keep the last authoritative progress value; result counters are not a
                            // reliable processed/total pair for a partially cancelled directory import.
                            progressDialog.updateStatus(i18n.text("ui.import.status.cancelled"));
                        } else {
                            progressDialog.updateProgress(total, total, i18n.text("ui.import.status.completed"));
                        }
                        closeProgressDialogAfter(Duration.seconds(1));
                    }

                    updateFinalStats(result.imported(), result.errors());
                    String summary = formatImportSummary(result, total);
                    if (cancelled) {
                        setStatus(summary + "\n\n" + i18n.text("ui.import.status.cancelled"));
                        appState.getStatusBar().setStatusText(i18n.text("ui.import.status.cancelled"));
                    } else {
                        setProgress(1.0);
                        setStatus(summary);
                        appState.getStatusBar().setStatusText(
                                i18n.format("ui.import.status.completed_summary",
                                        result.imported(), result.errors()));
                    }
                    if (!result.issues().isEmpty()) {
                        dialogService.showWarning(i18n.text("common.warning"),
                                i18n.text("ui.import.diagnostics.header"), formatImportIssues(result.issues()));
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        importRunning = false;
                        if (cancelButton != null) {
                            cancelButton.setDisable(true);
                        }
                        if (!isCurrentCollection(expectedCollectionId)) {
                            if (progressDialog != null) progressDialog.close();
                            return;
                        }
                        appState.getStatusBar().setProgressVisible(false);

                        if (progressDialog != null) {
                            progressDialog.updateStatus(i18n.format("common.error.with_message", UiExceptionSupport.message(ex)));
                            closeProgressDialogAfter(Duration.seconds(1.5));
                        }

                        if (cancelFlag.get()) {
                            setStatus(i18n.text("ui.import.status.cancelled"));
                        } else {
                            setStatus(i18n.format("ui.import.status.error", UiExceptionSupport.message(ex)));
                            dialogService.showError(i18n.text("common.error"), i18n.format("ui.import.error.failed", UiExceptionSupport.message(ex)));
                        }
                    });
                    log.error("Import failed", ex);
                    return null;
                });
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null || collection.getId() == null ? "" : collection.getId();
    }

    private boolean isCurrentCollection(String expectedCollectionId) {
        return java.util.Objects.equals(expectedCollectionId, currentCollectionId());
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
        DialogLocalization.prepare(dialog);
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

    private void updateOperationProgress(String expectedCollectionId, OperationProgress progress) {
        if (progress == null) return;
        operationCenter.accept(i18n.text("ui.import.operation.title"), expectedCollectionId, progress);
        if (!isCurrentCollection(expectedCollectionId)) return;
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

    private String formatImportIssues(List<ImportIssue> issues) {
        final int displayLimit = 20;
        StringBuilder out = new StringBuilder();
        int displayed = 0;
        for (ImportIssue issue : issues) {
            if (issue == null) continue;
            if (displayed >= displayLimit) break;
            if (displayed > 0) out.append("\n\n");
            out.append(safeDiagnosticText(issue.code(), 96)).append(" — ")
                    .append(issueMessage(issue));
            if (!issue.sourceRecord().isBlank()
                    && !"FILE_IMPORT_FAILED".equals(issue.code())
                    && !"IMPORT_ERRORS_REPORTED".equals(issue.code())) {
                out.append(" ( ").append(safeDiagnosticText(issue.sourceRecord(), 160)).append(" )");
            }
            displayed++;
        }
        if (issues.stream().anyMatch(issue -> issue != null && "ISSUES_TRUNCATED".equals(issue.code()))
                && issues.stream().limit(displayLimit).noneMatch(issue -> issue != null && "ISSUES_TRUNCATED".equals(issue.code()))) {
            ImportIssue truncated = issues.stream().filter(issue -> issue != null && "ISSUES_TRUNCATED".equals(issue.code())
                    ).findFirst().orElse(null);
            if (truncated != null) {
                if (out.length() > 0) out.append("\n\n");
                out.append(safeDiagnosticText(truncated.code(), 96)).append(" — ").append(issueMessage(truncated));
            }
        }
        if (issues.size() > displayLimit) {
            if (out.length() > 0) out.append("\n\n");
            out.append(i18n.format("ui.import.diagnostics.more", displayLimit, issues.size()));
        }
        return out.toString();
    }

    private String issueMessage(ImportIssue issue) {
        return switch (issue.code()) {
            case "FILE_IMPORT_FAILED" -> i18n.format("ui.import.diagnostics.file_failed",
                    safeDiagnosticText(issue.sourceRecord(), 160),
                    safeDiagnosticText(issue.context().getOrDefault("exceptionType", "Exception"), 80));
            case "IMPORT_ERRORS_REPORTED" -> i18n.format("ui.import.diagnostics.reported_errors",
                    safeDiagnosticText(issue.sourceRecord(), 160),
                    safeDiagnosticText(issue.context().getOrDefault("errorCount", "unknown"), 32));
            case "ISSUES_TRUNCATED" -> i18n.format("ui.import.diagnostics.truncated",
                    safeDiagnosticText(issue.context().getOrDefault("omittedCount", "unknown"), 32));
            default -> {
                String message = safeDiagnosticText(issue.message(), 400);
                yield message.isBlank() ? safeDiagnosticText(issue.code(), 96) : message;
            }
        };
    }

    private static String safeDiagnosticText(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cc}]", " ").trim();
        // Diagnostic text may originate in imported catalogues; do not echo absolute local paths in dialogs.
        normalized = normalized.replaceAll("(?i)\\b[A-Z]:\\\\[^\\s<>\"|?*]+", "[path]");
        normalized = normalized.replaceAll("(?<![A-Za-z0-9:/])/(?:[^\\s<>\"']+)", "[path]");
        if (normalized.length() <= maxLength) return normalized;
        return maxLength <= 1 ? normalized.substring(0, maxLength)
                : normalized.substring(0, maxLength - 1) + "…";
    }

    private void updateFinalStats(long imported, long errors) {
        UiExecutor.runOnUiThread(() -> {
            importedBooksLabel.setText(String.valueOf(imported));
            errorsLabel.setText(String.valueOf(errors));
        });
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