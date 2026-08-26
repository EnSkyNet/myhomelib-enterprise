package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.BackupRestoreService;
import com.myhomelibcorp.ui.service.DialogService;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class RestoreController {

    private final BackupRestoreService backupRestoreService;
    private final DialogService dialogService;

    @FXML private TextField backupPathField;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Label progressLabel;
    @FXML private TextArea logArea;
    @FXML private Button restoreButton;
    @FXML private Button selectPathButton;
    @FXML private Button cancelButton;
    @FXML private Button closeButton;
    @FXML private CheckBox restoreIndexCheckBox;
    @FXML private CheckBox restoreCoversCheckBox;
    @FXML private CheckBox restoreMetadataCheckBox;
    @FXML private CheckBox restoreDatabaseCheckBox;

    private volatile boolean cancelled = false;
    private Stage stage;
    private boolean restoreCompleted = false;

    @FXML
    public void initialize() {
        restoreIndexCheckBox.setSelected(true);
        restoreCoversCheckBox.setSelected(true);
        restoreMetadataCheckBox.setSelected(true);
        restoreDatabaseCheckBox.setSelected(true);
        restoreDatabaseCheckBox.selectedProperty().addListener((obs, oldValue, fullRestore) -> {
            restoreIndexCheckBox.setDisable(!fullRestore);
            restoreCoversCheckBox.setDisable(!fullRestore);
            if (!fullRestore) { restoreIndexCheckBox.setSelected(false); restoreCoversCheckBox.setSelected(false); restoreMetadataCheckBox.setSelected(true); }
        });

        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        cancelButton.setVisible(false);
        logArea.setVisible(false);
        statusLabel.setText("Виберіть папку з резервною копією");
        closeButton.setDisable(false);
        restoreButton.setDisable(true);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void onSelectPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку з резервною копією");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            backupPathField.setText(dir.getAbsolutePath());
            checkBackupContents(dir.toPath());
        }
    }

    private void checkBackupContents(Path backupPath) {
        logArea.setVisible(true);
        logArea.clear();
        addLog("Перевірка вмісту резервної копії...");

        boolean hasDb = false;
        boolean hasIndex = false;
        boolean hasCovers = false;
        boolean hasPortableUserData = false;

        try (var stream = Files.list(backupPath)) {
            var list = stream.toList();
            for (Path path : list) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db")) {
                    hasDb = true;
                    addLog("  ✅ База даних: " + name);
                } else if (name.equals("search-index") && Files.isDirectory(path)) {
                    hasIndex = true;
                    addLog("  ✅ Пошуковий індекс");
                } else if (name.equals("covers") && Files.isDirectory(path)) {
                    hasCovers = true;
                    addLog("  ✅ Обкладинки");
                } else if (name.equals("user-data.json") && Files.isRegularFile(path)) {
                    hasPortableUserData = true;
                    addLog("  ✅ Versioned user data: user-data.json");
                }
            }
        } catch (Exception e) {
            addLog("  ❌ Помилка перевірки: " + e.getMessage());
        }

        addLog("");
        if (!hasDb) {
            addLog("⚠️ Увага: Базу даних не знайдено!");
        }

        restoreButton.setDisable(!hasDb && !hasPortableUserData);
        if (hasDb) restoreDatabaseCheckBox.setSelected(true);
        else if (hasPortableUserData) restoreDatabaseCheckBox.setSelected(false);
        statusLabel.setText(hasDb || hasPortableUserData
                ? "✅ Резервна копія готова до відновлення"
                : "❌ Не знайдено базу даних або user-data.json");
    }

    @FXML
    public void onRestore() {
        String backupPath = backupPathField.getText().trim();
        if (backupPath.isEmpty()) {
            dialogService.showError("Помилка", "Виберіть папку з резервною копією.");
            return;
        }

        Path backupDir = Paths.get(backupPath);
        if (!Files.exists(backupDir) || !Files.isDirectory(backupDir)) {
            dialogService.showError("Помилка",
                    "Папка \"" + backupPath + "\" не існує або не є директорією.");
            return;
        }

        boolean restoreDatabase = restoreDatabaseCheckBox.isSelected();
        Path dbFile = findDbFile(backupDir);
        boolean hasPortable = Files.isRegularFile(backupDir.resolve("user-data.json"));
        if (restoreDatabase && dbFile == null) {
            dialogService.showError("Помилка", "Для повного відновлення не знайдено файл бази даних (.db).");
            return;
        }
        if (!restoreDatabase && !hasPortable) {
            dialogService.showError("Помилка", "Для перенесення користувацьких даних не знайдено user-data.json.");
            return;
        }

        String details = restoreDatabase
                ? "Папка: " + backupPath + "\nФайл бази даних: " + dbFile.getFileName()
                    + "\n\n⚠️ Поточна база каталогу буде замінена. Перед заміною SQLite handles закриваються, потім колекція відкривається знову."
                : "Папка: " + backupPath + "\n\nБаза каталогу НЕ замінюється. Ratings/progress/reviews/bookmarks/groups/history/filters/Reader settings будуть зіставлені за LibID.";
        if (!dialogService.showConfirmation("Відновлення з резервної копії",
                restoreDatabase ? "Виконати повне відновлення?" : "Перенести лише користувацькі дані?", details)) return;

        BackupRestoreService.RestoreOptions options = new BackupRestoreService.RestoreOptions(
                backupDir,
                restoreIndexCheckBox.isSelected(),
                restoreCoversCheckBox.isSelected(),
                restoreMetadataCheckBox.isSelected(),
                true,
                restoreDatabase
        );
        startRestore(backupPath, dbFile, options);
    }

    private Path findDbFile(Path backupDir) {
        try (var stream = Files.list(backupDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db")) {
                    return path;
                }
            }
        } catch (Exception e) {
            log.warn("Помилка пошуку бази даних: {}", e.getMessage());
        }
        return null;
    }

    private void startRestore(String backupPath, Path dbFile, BackupRestoreService.RestoreOptions options) {
        cancelled = false;
        restoreCompleted = false;
        restoreButton.setDisable(true);
        selectPathButton.setDisable(true);
        cancelButton.setVisible(true);
        cancelButton.setDisable(false);
        closeButton.setDisable(true);
        logArea.setVisible(true);
        logArea.clear();
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        statusLabel.setText("Відновлення...");

        new Thread(() -> {
            try {
                addLog("=== Початок відновлення ===");
                addLog("Джерело: " + backupPath);
                addLog("Режим: " + (options.restoreDatabase() ? "повне відновлення БД" : "лише user data за LibID"));
                if (dbFile != null) addLog("Файл бази даних: " + dbFile.getFileName());

                addLog("Відновлення...");
                BackupRestoreService.RestoreResult result = backupRestoreService.restore(options);

                UiExecutor.runOnUiThread(() -> {
                    if (result.isSuccess()) {
                        statusLabel.setText("✅ Відновлення завершено успішно!");
                        progressBar.setProgress(1.0);
                        progressLabel.setText("100%");
                        addLog("\n✅ Відновлення завершено успішно!");
                        restoreCompleted = true;
                        dialogService.showInfo("Успішно",
                                "Відновлення з резервної копії завершено успішно!\n\n" +
                                        "📁 Джерело: " + backupPath + "\n" +
                                        "📄 Відновлено елементів: " + result.itemsRestored());
                    } else {
                        statusLabel.setText("❌ Помилка: " + result.error());
                        addLog("\n❌ Помилка: " + result.error());
                        dialogService.showError("Помилка", "Не вдалося відновити: " + result.error());
                    }
                    resetUI();
                });

            } catch (Exception e) {
                log.error("Помилка відновлення", e);
                UiExecutor.runOnUiThread(() -> {
                    statusLabel.setText("❌ Помилка: " + e.getMessage());
                    addLog("\n❌ Помилка: " + e.getMessage());
                    dialogService.showError("Помилка", "Не вдалося відновити: " + e.getMessage());
                    resetUI();
                });
            }
        }).start();
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
            cancelButton.setVisible(false);
            closeButton.setDisable(false);
        });
    }

    @FXML
    public void onCancel() {
        cancelled = true;
        statusLabel.setText("Скасування...");
        cancelButton.setDisable(true);
    }

    @FXML
    public void onClose() {
        if (stage != null) {
            stage.close();
        }
    }
}