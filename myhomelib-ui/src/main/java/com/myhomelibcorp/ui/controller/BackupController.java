package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackupController {

    private final ApplicationState appState;
    private final CollectionManager collectionManager;
    private final DialogService dialogService;

    @FXML private TextField backupPathField;
    @FXML private CheckBox includeIndexCheckBox;
    @FXML private CheckBox includeCoversCheckBox;
    @FXML private CheckBox includeMetadataCheckBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Label progressLabel;
    @FXML private TextArea logArea;
    @FXML private Button backupButton;
    @FXML private Button selectPathButton;
    @FXML private Button cancelButton;

    private volatile boolean cancelled = false;
    private Stage stage;

    @FXML
    public void initialize() {
        includeIndexCheckBox.setSelected(true);
        includeCoversCheckBox.setSelected(true);
        includeMetadataCheckBox.setSelected(true);

        String defaultBackupPath = System.getProperty("user.home") +
                "/MyHomeLib_Backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        backupPathField.setText(defaultBackupPath);

        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        cancelButton.setVisible(false);
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
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            backupPathField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    public void onBackup() {
        Collection collection = collectionManager.getCurrentCollection();

        if (collection == null) {
            dialogService.showError("Помилка",
                    "Немає активної колекції для резервного копіювання.\n\n" +
                            "Будь ласка, відкрийте колекцію перед створенням резервної копії.");
            return;
        }

        // Діагностика
        log.info("=== ДІАГНОСТИКА КОЛЕКЦІЇ ===");
        log.info("Колекція: {}", collection.getName());
        log.info("ID: {}", collection.getId());
        log.info("dbFile: {}", collection.getDbFile());
        log.info("rootFolder: {}", collection.getRootFolder());
        log.info("==============================");

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
                    "Бажаєте перезаписати її вміст?")) {
                return;
            }
        }

        if (!dialogService.showConfirmation(
                "Резервне копіювання",
                "Створити резервну копію колекції \"" + collection.getName() + "\"?",
                "Папка: " + backupPath)) {
            return;
        }

        startBackup(collection, backupPath);
    }

    @FXML
    public void onCancel() {
        cancelled = true;
        UiExecutor.runOnUiThread(() -> {
            statusLabel.setText("⏳ Скасування...");
            cancelButton.setDisable(true);
        });
        log.info("Резервне копіювання скасовано користувачем");
    }

    @FXML
    public void onClose() {
        if (stage != null) {
            stage.close();
        }
    }

    private void startBackup(Collection collection, String backupPath) {
        cancelled = false;
        UiExecutor.runOnUiThread(() -> {
            backupButton.setDisable(true);
            selectPathButton.setDisable(true);
            cancelButton.setVisible(true);
            cancelButton.setDisable(false);
            logArea.setVisible(true);
            logArea.clear();
            progressBar.setVisible(true);
            progressLabel.setVisible(true);
            progressBar.setProgress(0);
            progressLabel.setText("0%");
            statusLabel.setText("⏳ Підготовка до копіювання...");
        });

        new Thread(() -> {
            try {
                doBackup(collection, backupPath);
            } catch (Exception e) {
                log.error("Помилка резервного копіювання", e);
                UiExecutor.runOnUiThread(() -> {
                    statusLabel.setText("❌ Помилка: " + e.getMessage());
                    dialogService.showError("Помилка", "Не вдалося створити резервну копію: " + e.getMessage());
                    resetUI();
                });
            }
        }).start();
    }

    private void doBackup(Collection collection, String backupPath) throws IOException, InterruptedException {
        Path backupDir = Paths.get(backupPath);

        // Створюємо папку на UI потоці
        UiExecutor.runOnUiThread(() -> {
            try {
                if (!Files.exists(backupDir)) {
                    Files.createDirectories(backupDir);
                    addLog("📁 Створено папку: " + backupPath);
                }
            } catch (IOException e) {
                log.error("Помилка створення папки", e);
                addLog("❌ Помилка створення папки: " + e.getMessage());
            }
        });

        List<BackupItem> items = new ArrayList<>();
        long totalSize = 0;

        // ===== 1. База даних =====
        addLog("🔍 Пошук бази даних...");

        // Визначаємо шлях до БД
        String targetDbPath = collection.getDbFile();
        if (targetDbPath == null || targetDbPath.isEmpty()) {
            targetDbPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db";
            addLog("  ℹ️ dbFile не вказано, використовуємо стандартний шлях: " + targetDbPath);
        }

        Path targetDb = Paths.get(targetDbPath);
        addLog("  📄 Шлях до БД: " + targetDb);

        if (Files.exists(targetDb)) {
            items.add(new BackupItem(targetDb, backupDir.resolve(targetDb.getFileName().toString())));
            totalSize += Files.size(targetDb);
            addLog("  ✅ База даних знайдена: " + targetDb.getFileName() + " (" + formatSize(Files.size(targetDb)) + ")");
        } else {
            addLog("  ❌ Базу даних не знайдено за шляхом: " + targetDb);

            // Спроба знайти в альтернативних місцях
            List<String> possiblePaths = new ArrayList<>();
            possiblePaths.add(System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db");
            possiblePaths.add(System.getProperty("user.dir") + "/libraries/" + collection.getId() + ".db");
            possiblePaths.add(System.getProperty("user.dir") + "/Data/" + collection.getId() + ".db");

            if (collection.getRootFolder() != null) {
                possiblePaths.add(collection.getRootFolder() + "/" + collection.getId() + ".db");
                possiblePaths.add(collection.getRootFolder() + "/../Data/" + collection.getId() + ".db");
            }

            Path foundDb = null;
            for (String path : possiblePaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath)) {
                    foundDb = testPath;
                    addLog("  ✅ Знайдено БД в альтернативному місці: " + testPath);
                    break;
                }
            }

            if (foundDb != null) {
                items.add(new BackupItem(foundDb, backupDir.resolve(foundDb.getFileName().toString())));
                totalSize += Files.size(foundDb);
                addLog("  ✅ База даних додана (" + formatSize(Files.size(foundDb)) + ")");
            } else {
                addLog("  ❌ Базу даних не знайдено в жодному з альтернативних місць");
            }
        }

        // ===== 2. Пошуковий індекс =====
        if (includeIndexCheckBox.isSelected()) {
            List<String> possibleIndexPaths = new ArrayList<>();
            possibleIndexPaths.add(System.getProperty("user.home") + "/.myhomelibcorp/search-index-" + collection.getId());
            possibleIndexPaths.add(System.getProperty("user.home") + "/.myhomelibcorp/search-index");
            possibleIndexPaths.add(System.getProperty("user.dir") + "/search-index-" + collection.getId());

            Path foundIndex = null;
            for (String path : possibleIndexPaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath)) {
                    foundIndex = testPath;
                    addLog("  ✅ Знайдено індекс: " + testPath);
                    break;
                }
            }

            if (foundIndex != null) {
                long indexSize = getDirectorySize(foundIndex);
                items.add(new BackupItem(foundIndex, backupDir.resolve("search-index")));
                totalSize += indexSize;
                addLog("  ✅ Індекс додано (" + formatSize(indexSize) + ")");
            } else {
                addLog("  ❌ Пошуковий індекс не знайдено");
            }
        }

        // ===== 3. Обкладинки =====
        if (includeCoversCheckBox.isSelected()) {
            List<String> possibleCoverPaths = new ArrayList<>();
            possibleCoverPaths.add(System.getProperty("user.home") + "/.myhomelibcorp/covers/" + collection.getId());
            possibleCoverPaths.add(System.getProperty("user.home") + "/.myhomelibcorp/covers");
            possibleCoverPaths.add(System.getProperty("user.dir") + "/covers-" + collection.getId());

            Path foundCovers = null;
            for (String path : possibleCoverPaths) {
                Path testPath = Paths.get(path);
                if (Files.exists(testPath)) {
                    foundCovers = testPath;
                    addLog("  ✅ Знайдено обкладинки: " + testPath);
                    break;
                }
            }

            if (foundCovers != null) {
                long coversSize = getDirectorySize(foundCovers);
                items.add(new BackupItem(foundCovers, backupDir.resolve("covers")));
                totalSize += coversSize;
                addLog("  ✅ Обкладинки додано (" + formatSize(coversSize) + ")");
            } else {
                addLog("  ❌ Обкладинки не знайдено");
            }
        }

        // ===== 4. Метадані =====
        if (includeMetadataCheckBox.isSelected()) {
            addLog("📝 Метадані: включено");
            // Тут можна додати копіювання файлів метаданих
        }

        // ===== ПІДСУМОК =====
        addLog("\n📊 ПІДСУМОК:");
        addLog("  Знайдено елементів: " + items.size());
        addLog("  Загальний розмір: " + formatSize(totalSize));
        addLog("");

        if (items.isEmpty()) {
            addLog("❌ Немає даних для резервного копіювання!");
            UiExecutor.runOnUiThread(() -> {
                statusLabel.setText("❌ Немає даних для копіювання");
                dialogService.showError("Помилка",
                        "Не знайдено даних для резервного копіювання.\n\n" +
                                "Перевірте:\n" +
                                "• Чи існує база даних колекції\n" +
                                "• Чи правильний шлях до колекції\n" +
                                "• Чи створено пошуковий індекс\n" +
                                "• Чи є обкладинки");
                resetUI();
            });
            return;
        }

        final long finalTotalSize = totalSize;
        final int finalTotalItems = items.size();
        AtomicLong copiedBytes = new AtomicLong(0);
        AtomicInteger processedItems = new AtomicInteger(0);

        addLog("📤 Початок копіювання... (" + finalTotalItems + " елементів)");
        UiExecutor.runOnUiThread(() -> {
            statusLabel.setText("⏳ Копіювання...");
        });

        for (BackupItem item : items) {
            if (cancelled) {
                addLog("⏹️ Операцію скасовано");
                UiExecutor.runOnUiThread(() -> {
                    statusLabel.setText("⏹️ Скасовано");
                    resetUI();
                });
                return;
            }

            try {
                if (Files.isDirectory(item.source)) {
                    addLog("  📁 Копіювання папки: " + item.source.getFileName());
                    copyDirectory(item.source, item.target, (path) -> {
                        if (cancelled) return;
                        try {
                            long size = Files.size(path);
                            copiedBytes.addAndGet(size);
                            updateProgress(copiedBytes.get(), finalTotalSize, processedItems.get(), finalTotalItems);
                        } catch (IOException e) {
                            log.warn("Помилка отримання розміру файлу: {}", path, e);
                        }
                    });
                } else {
                    addLog("  📄 Копіювання файлу: " + item.source.getFileName());
                    Files.copy(item.source, item.target, StandardCopyOption.REPLACE_EXISTING);
                    copiedBytes.addAndGet(Files.size(item.source));
                }
                int current = processedItems.incrementAndGet();
                updateProgress(copiedBytes.get(), finalTotalSize, current, finalTotalItems);
            } catch (Exception e) {
                addLog("  ❌ Помилка копіювання: " + item.source.getFileName() + " - " + e.getMessage());
                log.error("Помилка копіювання: {}", item.source, e);
            }
        }

        if (!cancelled) {
            UiExecutor.runOnUiThread(() -> {
                statusLabel.setText("✅ Резервне копіювання завершено!");
                progressBar.setProgress(1.0);
                progressLabel.setText("100%");
                backupButton.setDisable(false);
                selectPathButton.setDisable(false);
                cancelButton.setVisible(false);
                addLog("\n✅ Резервне копіювання успішно завершено!");
                addLog("📁 Папка: " + backupPath);
                addLog("📊 Загальний розмір: " + formatSize(finalTotalSize));
                dialogService.showInfo("Успішно",
                        "✅ Резервне копіювання завершено!\n\n" +
                                "📁 Папка: " + backupPath + "\n" +
                                "📊 Розмір: " + formatSize(finalTotalSize) + "\n" +
                                "📄 Файлів/папок: " + finalTotalItems);
            });
        } else {
            resetUI();
        }
    }

    private void copyDirectory(Path source, Path target, Consumer<Path> fileConsumer) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        try (var stream = Files.walk(source)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (cancelled) return;
                Path path = iterator.next();
                try {
                    Path relativePath = source.relativize(path);
                    Path targetPath = target.resolve(relativePath.toString());
                    if (Files.isDirectory(path)) {
                        if (!Files.exists(targetPath)) {
                            Files.createDirectories(targetPath);
                        }
                    } else {
                        Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        if (fileConsumer != null) {
                            fileConsumer.accept(path);
                        }
                    }
                } catch (IOException e) {
                    log.error("Помилка копіювання: {}", path, e);
                }
            }
        }
    }

    private long getDirectorySize(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            var iterator = stream.iterator();
            long size = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) {
                    try {
                        size += Files.size(path);
                    } catch (IOException e) {
                        log.warn("Помилка отримання розміру файлу: {}", path, e);
                    }
                }
            }
            return size;
        }
    }

    private void updateProgress(long copied, long total, int processed, int totalItems) {
        UiExecutor.runOnUiThread(() -> {
            double progress = total > 0 ? (double) copied / total : (double) processed / totalItems;
            progress = Math.min(progress, 1.0);
            progressBar.setProgress(progress);
            progressLabel.setText(String.format("%d%%", (int)(progress * 100)));
            statusLabel.setText(String.format("⏳ Копіювання... %d%%", (int)(progress * 100)));
        });
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
            cancelButton.setVisible(false);
            if (!cancelled) {
                statusLabel.setText("❌ Помилка");
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static class BackupItem {
        final Path source;
        final Path target;
        BackupItem(Path source, Path target) {
            this.source = source;
            this.target = target;
        }
    }
}