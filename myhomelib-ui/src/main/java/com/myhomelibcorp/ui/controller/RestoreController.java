package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.service.BackupRestoreService;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
@Slf4j
public class RestoreController {

    private final BackupRestoreService backupRestoreService;
    private final DialogService dialogService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;
    private final ApplicationState appState;

    @FXML private TextField backupPathField;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;
    @FXML private Button restoreButton;
    @FXML private Button selectPathButton;
    @FXML private Button closeButton;
    @FXML private CheckBox restoreMetadataCheckBox;
    @FXML private CheckBox restoreDatabaseCheckBox;

    private Stage stage;

    @FXML
    public void initialize() {
        restoreMetadataCheckBox.setSelected(true);
        restoreDatabaseCheckBox.setSelected(true);
        restoreDatabaseCheckBox.selectedProperty().addListener((obs, oldValue, fullRestore) -> {
            if (!fullRestore) restoreMetadataCheckBox.setSelected(true);
        });

        progressBar.setVisible(false);
        logArea.setVisible(false);
        statusLabel.setText(UiText.text("ui.fxml.restore_dialog.select_the_backup_folder_697a0c0"));
        closeButton.setDisable(false);
        restoreButton.setDisable(true);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void onSelectPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(UiText.text("ui.fxml.restore_dialog.select_the_backup_folder_697a0c0"));
        chooser.setInitialDirectory(AppPaths.backupsDir().toFile());
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            backupPathField.setText(dir.getAbsolutePath());
            checkBackupContents(dir.toPath());
        }
    }

    private void checkBackupContents(Path backupPath) {
        logArea.setVisible(true);
        logArea.clear();
        addLog(UiText.text("ui.java.restore_controller.368673e840"));

        boolean hasDb = false;
        boolean hasPortableUserData = false;

        try {
            Path safeBackupPath = SafeFilePublication.requireSafeDirectoryPath(backupPath, "Backup selection directory");
            try (var stream = Files.list(safeBackupPath)) {
            var list = stream.toList();
            for (Path path : list) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    hasDb = true;
                    addLog(UiText.text("ui.java.restore_controller.44cc2f53e0") + name);
                } else if (name.equals("search-index") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    addLog(UiText.text("ui.java.restore_controller.ab7894c6e5"));
                } else if (name.equals("covers") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    addLog(UiText.text("ui.java.restore_controller.023a4f1138"));
                } else if (name.equals("user-data.json") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    hasPortableUserData = true;
                    addLog(UiText.text("ui.java.restore_controller.bebeb0b699"));
                }
            }
            }
        } catch (Exception e) {
            addLog(UiText.text("ui.java.restore_controller.ba15ce628e") + UiExceptionSupport.message(e));
        }

        addLog("");
        if (!hasDb) {
            addLog(UiText.text("ui.java.restore_controller.6ea0c22a07"));
        }

        restoreButton.setDisable(!hasDb && !hasPortableUserData);
        if (hasDb) restoreDatabaseCheckBox.setSelected(true);
        else if (hasPortableUserData) restoreDatabaseCheckBox.setSelected(false);
        statusLabel.setText(hasDb || hasPortableUserData
                ? UiText.text("ui.java.restore_controller.d4174d4c5f")
                : UiText.text("ui.java.restore_controller.c9fd27eed9"));
    }

    @FXML
    public void onRestore() {
        String backupPath = backupPathField.getText().trim();
        if (backupPath.isEmpty()) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.restore_controller.665d229311"));
            return;
        }

        Path backupDir = Paths.get(backupPath);
        if (!Files.exists(backupDir, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) {
            dialogService.showError(UiText.text("common.error"),
                    UiText.text("ui.java.restore_controller.f6ffae3247") + backupPath + UiText.text("ui.java.restore_controller.9c9f6c6535"));
            return;
        }
        try {
            backupDir = SafeFilePublication.requireSafeDirectoryPath(backupDir, "Backup restore directory");
        } catch (Exception unsafe) {
            dialogService.showError(UiText.text("common.error"), UiExceptionSupport.message(unsafe));
            return;
        }

        boolean restoreDatabase = restoreDatabaseCheckBox.isSelected();
        Path dbFile = findDbFile(backupDir);
        boolean hasPortable = Files.isRegularFile(backupDir.resolve("user-data.json"), LinkOption.NOFOLLOW_LINKS);
        if (restoreDatabase && dbFile == null) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.restore_controller.19775dd9f3"));
            return;
        }
        if (!restoreDatabase && !hasPortable) {
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.restore_controller.2636566b30"));
            return;
        }

        String details = restoreDatabase
                ? UiText.text("ui.java.restore_controller.5c7051dc85") + backupPath + UiText.text("ui.java.restore_controller.bb3b7e95bc") + dbFile.getFileName()
                    + UiText.text("ui.java.restore_controller.d5a6bdb5ff")
                : UiText.text("ui.java.restore_controller.5c7051dc85") + backupPath + UiText.text("ui.java.restore_controller.65977f193f");
        if (!dialogService.showConfirmation(UiText.text("ui.java.restore_controller.1cc5a302ca"),
                restoreDatabase ? UiText.text("ui.java.restore_controller.9d18bcbb34") : UiText.text("ui.java.restore_controller.c04265ad83"), details)) return;

        BackupRestoreService.RestoreOptions options = new BackupRestoreService.RestoreOptions(
                backupDir,
                restoreMetadataCheckBox.isSelected(),
                true,
                restoreDatabase
        );
        startRestore(backupPath, dbFile, options);
    }

    private Path findDbFile(Path backupDir) {
        try {
            Path safeBackupDir = SafeFilePublication.requireSafeDirectoryPath(backupDir, "Backup database scan directory");
            try (var stream = Files.list(safeBackupDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return path;
                }
            }
            }
        } catch (Exception e) {
            log.warn("Помилка пошуку бази даних: {}", e.getMessage());
        }
        return null;
    }

    private void startRestore(String backupPath, Path dbFile, BackupRestoreService.RestoreOptions options) {
        restoreButton.setDisable(true);
        selectPathButton.setDisable(true);
        closeButton.setDisable(true);
        logArea.setVisible(true);
        logArea.clear();
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        statusLabel.setText(UiText.text("ui.java.restore_controller.68f7a4922d"));

        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                UiText.text("ui.java.restore_controller.1cc5a302ca"), collection == null ? "" : collection.getId(),
                OperationStage.RESTORING, false);
        addLog(UiText.text("ui.java.restore_controller.dbd8425764"));
        addLog(UiText.text("ui.java.restore_controller.90ba2d4947") + backupPath);
        addLog(UiText.text("ui.java.restore_controller.84f39aaabe") + (options.restoreDatabase() ? UiText.text("ui.java.restore_controller.2365e69fa6") : UiText.text("ui.java.restore_controller.26318998d5")));
        if (dbFile != null) addLog(UiText.text("ui.java.restore_controller.237a88531c") + dbFile.getFileName());
        addLog(UiText.text("ui.java.restore_controller.68f7a4922d"));

        String expectedCollectionId = collection == null ? null : collection.getId();
        executor.submit(() -> backupRestoreService.restore(options, expectedCollectionId))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка відновлення", cause);
                        statusLabel.setText(UiText.text("ui.java.restore_controller.a649591c3d") + UiExceptionSupport.message(cause));
                        addLog(UiText.text("ui.java.restore_controller.dba48a8828") + UiExceptionSupport.message(cause));
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.restore_controller.8619337fad") + UiExceptionSupport.message(cause));
                    } else if (result != null && result.isSuccess()) {
                        String indexWarning = result.indexRebuildPending()
                                ? UiText.text("ui.java.duplicate_review_ui_service.e12ccbbe50")
                                : "";
                        operationCenter.complete(operationId,
                                UiText.text("ui.java.restore_controller.472aed85f1") + result.itemsRestored() + indexWarning);
                        statusLabel.setText(UiText.text("ui.java.restore_controller.e8b65f3cd1"));
                        progressBar.setProgress(1.0);
                        addLog(UiText.text("ui.java.restore_controller.6174d71253"));
                        if (!indexWarning.isBlank()) addLog(indexWarning.stripLeading());
                        dialogService.showInfo(UiText.text("common.success"),
                                UiText.text("ui.java.restore_controller.0036c70464") +
                                        UiText.text("ui.java.restore_controller.3c763ad280") + backupPath + "\n" +
                                        UiText.text("ui.java.restore_controller.8bbc8a21d4") + result.itemsRestored()
                                        + indexWarning);
                    } else {
                        String technical = result == null ? "" : result.error();
                        if (!technical.isBlank()) log.error("Restore reported failure: {}", technical);
                        String message = result == null ? UiText.text("common.result.unknown") : UiText.text("common.error.operation_failed");
                        operationCenter.fail(operationId, new IllegalStateException(technical.isBlank() ? message : technical));
                        statusLabel.setText(UiText.text("ui.java.restore_controller.a649591c3d") + message);
                        addLog(UiText.text("ui.java.restore_controller.dba48a8828") + message);
                        addLog(UiText.text("common.error.details_in_log"));
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.restore_controller.8619337fad") + message);
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
            restoreButton.setDisable(false);
            selectPathButton.setDisable(false);
                closeButton.setDisable(false);
        });
    }

    @FXML
    public void onClose() {
        if (stage != null) {
            stage.close();
        }
    }
}