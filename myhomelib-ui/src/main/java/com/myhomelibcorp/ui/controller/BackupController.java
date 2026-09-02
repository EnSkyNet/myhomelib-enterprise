package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.BackupRestoreService;
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
        statusLabel.setText("Готово до резервного копіювання");
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void onSelectPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку для резервної копії");
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
            dialogService.showError("Помилка", "Виберіть папку для резервної копії.");
            return;
        }

        Path backupDir = Paths.get(backupPath);
        if (Files.exists(backupDir)) {
            if (!dialogService.showConfirmation(
                    "Папка існує",
                    "Папка \"" + backupPath + "\" вже існує.",
                    "Бажаєте перезаписати її?")) {
                return;
            }
        }

        if (!dialogService.showConfirmation(
                "Резервне копіювання",
                "Створити резервну копію поточної колекції?",
                "Папка: " + backupPath)) {
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
            statusLabel.setText("Створення резервної копії...");
        });

        BackupRestoreService.BackupOptions options = new BackupRestoreService.BackupOptions(
                backupDir, includeMetadataCheckBox.isSelected());
        var collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                "Резервна копія", collection == null ? "" : collection.getId(), OperationStage.BACKING_UP, false);

        addLog("Початок резервного копіювання...");
        addLog("Папка: " + backupDir);
        executor.submit(() -> backupRestoreService.backup(options))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка резервного копіювання", cause);
                        statusLabel.setText("Помилка: " + cause.getMessage());
                        addLog("\n❌ Помилка: " + cause.getMessage());
                        dialogService.showError("Помилка", "Не вдалося створити резервну копію: " + cause.getMessage());
                    } else if (result != null && result.isSuccess()) {
                        operationCenter.complete(operationId, "Скопійовано елементів: " + result.itemsCopied());
                        statusLabel.setText("Резервне копіювання завершено успішно!");
                        progressBar.setProgress(1.0);
                        addLog("\n✅ Резервне копіювання завершено успішно!");
                        addLog("📁 Папка: " + backupDir);
                        addLog("📄 Скопійовано елементів: " + result.itemsCopied());
                        dialogService.showInfo("Успішно",
                                "Резервне копіювання завершено успішно!\n\n" +
                                        "📁 Папка: " + backupDir + "\n" +
                                        "📄 Скопійовано елементів: " + result.itemsCopied());
                    } else {
                        String message = result == null ? "Невідомий результат" : result.error();
                        operationCenter.fail(operationId, new IllegalStateException(message));
                        statusLabel.setText("Помилка: " + message);
                        addLog("\n❌ Помилка: " + message);
                        dialogService.showError("Помилка", "Не вдалося створити резервну копію: " + message);
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