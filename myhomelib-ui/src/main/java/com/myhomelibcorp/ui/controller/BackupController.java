package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.BackupRestoreService;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackupController {

    private final BackupRestoreService backupRestoreService;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final ApplicationState appState;
    private final LocalizationService i18n;

    @FXML private TextField backupPathField;
    @FXML private CheckBox includeMetadataCheckBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;
    @FXML private Button backupButton;
    @FXML private Button selectPathButton;

    private Stage stage;

    @FXML
    public void initialize() {
        includeMetadataCheckBox.setSelected(true);

        String backupName = "MyHomeLib_Backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        backupPathField.setText(AppPaths.backupsDir().resolve(backupName).toString());

        progressBar.setVisible(false);
        logArea.setVisible(false);
        statusLabel.setText(i18n.text("ui.backup.status.ready"));
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void onSelectPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n.text("ui.backup.choose_folder.title"));
        chooser.setInitialDirectory(AppPaths.backupsDir().toFile());
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            backupPathField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    public void onBackup() {
        String backupPath = backupPathField.getText().trim();
        if (backupPath.isEmpty()) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.backup.error.choose_folder"));
            return;
        }

        Path backupDir = Paths.get(backupPath);
        if (Files.exists(backupDir)) {
            if (!dialogService.showConfirmation(
                    i18n.text("ui.backup.confirm.exists.title"),
                    i18n.format("ui.backup.confirm.exists.header", backupPath),
                    i18n.text("ui.backup.confirm.exists.content"))) {
                return;
            }
        }

        if (!dialogService.showConfirmation(
                i18n.text("ui.backup.confirm.start.title"),
                i18n.text("ui.backup.confirm.start.header"),
                i18n.format("ui.backup.path", backupPath))) {
            return;
        }

        startBackup(backupDir);
    }

    @FXML
    public void onClose() {
        if (stage != null) {
            stage.close();
        }
    }

    private void startBackup(Path backupDir) {
        UiExecutor.runOnUiThread(() -> {
            backupButton.setDisable(true);
            selectPathButton.setDisable(true);
            logArea.setVisible(true);
            logArea.clear();
            progressBar.setVisible(true);
            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            statusLabel.setText(i18n.text("ui.backup.status.running"));
        });

        BackupRestoreService.BackupOptions options = new BackupRestoreService.BackupOptions(
                backupDir, includeMetadataCheckBox.isSelected());
        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                i18n.text("ui.backup.operation.title"), collection == null ? "" : collection.getId(), OperationStage.BACKING_UP, false);

        addLog(i18n.text("ui.backup.log.start"));
        addLog(i18n.format("ui.backup.path", backupDir));
        executor.submit(() -> backupRestoreService.backup(options))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка резервного копіювання", cause);
                        statusLabel.setText(i18n.format("common.error.with_message", cause.getMessage()));
                        addLog("\n" + i18n.format("ui.backup.log.error", cause.getMessage()));
                        dialogService.showError(i18n.text("common.error"), i18n.format("ui.backup.error.failed", cause.getMessage()));
                    } else if (result != null && result.isSuccess()) {
                        operationCenter.complete(operationId, i18n.format("ui.backup.items_copied", result.itemsCopied()));
                        statusLabel.setText(i18n.text("ui.backup.status.success"));
                        progressBar.setProgress(1.0);
                        addLog("\n" + i18n.text("ui.backup.log.success"));
                        addLog(i18n.format("ui.backup.log.folder", backupDir));
                        addLog(i18n.format("ui.backup.log.items_copied", result.itemsCopied()));
                        dialogService.showInfo(i18n.text("common.success"),
                                i18n.format("ui.backup.success.details", backupDir, result.itemsCopied()));
                    } else {
                        String message = result == null ? i18n.text("common.result.unknown") : result.error();
                        operationCenter.fail(operationId, new IllegalStateException(message));
                        statusLabel.setText(i18n.format("common.error.with_message", message));
                        addLog("\n" + i18n.format("ui.backup.log.error", message));
                        dialogService.showError(i18n.text("common.error"), i18n.format("ui.backup.error.failed", message));
                    }
                    resetUI();
                }));
    }

    private void addLog(String message) {
        UiExecutor.runOnUiThread(() -> {
            logArea.appendText(message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void resetUI() {
        UiExecutor.runOnUiThread(() -> {
            backupButton.setDisable(false);
            selectPathButton.setDisable(false);
        });
    }
}