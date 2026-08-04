package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.cleanup.DatabaseConnectionCleanup;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.search.LuceneSearchService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class RestoreController {

    private final ApplicationState appState;
    private final CollectionManager collectionManager;
    private final DialogService dialogService;
    private final DatabaseConnectionCleanup cleanup;
    private final DictionaryCachePort dictionaryCache;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final GroupRepository groupRepository;
    private final StatisticsService statisticsService;
    private final LuceneSearchService luceneSearchService;
    private final BookQueryRepository bookQueryRepository;

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

    private volatile boolean cancelled = false;
    private Stage stage;
    private boolean restoreCompleted = false;
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;

    @FXML
    public void initialize() {
        restoreIndexCheckBox.setSelected(true);
        restoreCoversCheckBox.setSelected(true);
        restoreMetadataCheckBox.setSelected(true);

        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        cancelButton.setVisible(false);
        logArea.setVisible(false);
        statusLabel.setText("Виберіть папку з резервною копією");
        closeButton.setDisable(false);
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
        addLog("🔍 Перевірка вмісту резервної копії...");

        boolean hasDb = false;
        boolean hasIndex = false;
        boolean hasCovers = false;

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
                }
            }
        } catch (IOException e) {
            addLog("  ❌ Помилка перевірки: " + e.getMessage());
        }

        addLog("");
        if (!hasDb) {
            addLog("⚠️ Увага: базу даних не знайдено!");
        }

        restoreButton.setDisable(!hasDb);
        statusLabel.setText(hasDb ? "✅ Резервна копія готова до відновлення" : "❌ Не знайдено базу даних");
    }

    private Path findDbFile(Path backupDir) {
        try (var stream = Files.list(backupDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db")) {
                    return path;
                }
            }
        } catch (IOException e) {
            log.warn("Помилка пошуку БД у резервній копії: {}", e.getMessage());
        }
        return null;
    }

    @FXML
    public void onRestore() {
        Collection collection = collectionManager.getCurrentCollection();
        if (collection == null) {
            dialogService.showError("Помилка", "Немає активної колекції для відновлення.");
            return;
        }

        String backupPath = backupPathField.getText().trim();
        if (backupPath.isEmpty()) {
            dialogService.showError("Помилка", "Виберіть папку з резервною копією.");
            return;
        }

        Path backupDir = Paths.get(backupPath);

        if (!Files.exists(backupDir) || !Files.isDirectory(backupDir)) {
            dialogService.showError("Помилка", "Папка \"" + backupPath + "\" не існує або не є директорією.");
            return;
        }

        Path dbFile = findDbFile(backupDir);
        if (dbFile == null) {
            dialogService.showError("Помилка",
                    "Не знайдено файл бази даних (.db) у резервній копії.\n\n" +
                            "Переконайтеся, що вибрана правильна папка з резервною копією.");
            return;
        }

        if (!dialogService.showConfirmation(
                "Відновлення з копії",
                "Відновити колекцію \"" + collection.getName() + "\" з резервної копії?",
                "Папка: " + backupPath + "\n" +
                        "Файл БД: " + dbFile.getFileName() + "\n\n" +
                        "⚠️ Увага! Поточні дані будуть замінені!\n" +
                        "Рекомендується зробити резервну копію перед відновленням.")) {
            return;
        }

        startRestore(collection, backupPath, dbFile, backupDir);
    }

    private void startRestore(Collection collection, String backupPath, Path dbFile, Path backupDir) {
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
        statusLabel.setText("⏳ Закриття колекції...");

        new Thread(() -> {
            try {
                // ========== ПОВНЕ ОЧИЩЕННЯ РЕСУРСІВ ==========
                addLog("🧹 Очищення всіх ресурсів...");

                cleanup.cleanupAll();
                addLog("✅ Ресурси очищено");

                Thread.sleep(1000);

                // ========== КОПІЮВАННЯ ФАЙЛІВ ==========
                String targetDbPath = collection.getDbFile();
                if (targetDbPath == null || targetDbPath.isEmpty()) {
                    targetDbPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db";
                    addLog("  ℹ️ dbFile не вказано, використовуємо стандартний шлях: " + targetDbPath);
                }

                Path targetDb = Paths.get(targetDbPath);
                Files.createDirectories(targetDb.getParent());

                // Видаляємо старий файл з повторними спробами
                boolean fileHandled = false;
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    if (cancelled) {
                        addLog("⏹️ Операцію скасовано");
                        UiExecutor.runOnUiThread(() -> {
                            statusLabel.setText("⏹️ Скасовано");
                            resetUI();
                        });
                        return;
                    }

                    if (!Files.exists(targetDb)) {
                        fileHandled = true;
                        addLog("  ℹ️ Файл БД не існує, пропускаємо");
                        break;
                    }

                    addLog("  ⏳ Спроба " + attempt + "/" + MAX_RETRIES + " обробки файлу: " + targetDb);

                    try {
                        Files.deleteIfExists(targetDb);
                        addLog("  ✅ Файл БД видалено (спроба " + attempt + ")");
                        fileHandled = true;
                        break;
                    } catch (IOException e) {
                        if (attempt < MAX_RETRIES) {
                            addLog("  ⚠️ Не вдалося видалити (спроба " + attempt + "): " + e.getMessage());

                            try {
                                Path backupOld = targetDb.getParent().resolve(targetDb.getFileName() + ".old_" + System.currentTimeMillis());
                                Files.move(targetDb, backupOld, StandardCopyOption.REPLACE_EXISTING);
                                addLog("  ✅ Файл перейменовано в: " + backupOld.getFileName());
                                fileHandled = true;
                                break;
                            } catch (IOException e2) {
                                addLog("  ⚠️ Не вдалося перейменувати: " + e2.getMessage());
                            }

                            Thread.sleep(RETRY_DELAY_MS);
                        } else {
                            addLog("  ❌ Не вдалося обробити файл після " + MAX_RETRIES + " спроб");
                            throw e;
                        }
                    }
                }

                if (!fileHandled) {
                    throw new IOException("Не вдалося обробити файл БД");
                }

                // Копіюємо нову БД
                addLog("  📄 Копіювання БД: " + dbFile.getFileName() + " -> " + targetDb);
                Files.copy(dbFile, targetDb, StandardCopyOption.REPLACE_EXISTING);
                addLog("  ✅ БД скопійовано (" + formatSize(Files.size(dbFile)) + ")");

                // ===== 2. Пошуковий індекс =====
                if (restoreIndexCheckBox.isSelected()) {
                    Path backupIndexDir = backupDir.resolve("search-index");
                    if (Files.exists(backupIndexDir)) {
                        String targetIndexPath = System.getProperty("user.home") + "/.myhomelibcorp/search-index-" + collection.getId();
                        Path targetIndex = Paths.get(targetIndexPath);

                        if (Files.exists(targetIndex)) {
                            addLog("  ⏳ Видалення старого індексу...");
                            deleteDirectory(targetIndex);
                            Thread.sleep(200);
                        }

                        addLog("  📁 Копіювання індексу...");
                        copyDirectory(backupIndexDir, targetIndex);
                        addLog("  ✅ Індекс скопійовано (" + formatSize(getDirectorySize(backupIndexDir)) + ")");
                    } else {
                        addLog("  ❌ Індекс не знайдено");
                    }
                }

                // ===== 3. Обкладинки =====
                if (restoreCoversCheckBox.isSelected()) {
                    Path backupCoversDir = backupDir.resolve("covers");
                    if (Files.exists(backupCoversDir)) {
                        String targetCoversPath = System.getProperty("user.home") + "/.myhomelibcorp/covers/" + collection.getId();
                        Path targetCovers = Paths.get(targetCoversPath);

                        if (Files.exists(targetCovers)) {
                            addLog("  ⏳ Видалення старих обкладинок...");
                            deleteDirectory(targetCovers);
                            Thread.sleep(200);
                        }

                        addLog("  📁 Копіювання обкладинок...");
                        copyDirectory(backupCoversDir, targetCovers);
                        addLog("  ✅ Обкладинки скопійовано (" + formatSize(getDirectorySize(backupCoversDir)) + ")");
                    } else {
                        addLog("  ❌ Обкладинки не знайдено");
                    }
                }

                // ===== 4. Метадані =====
                if (restoreMetadataCheckBox.isSelected()) {
                    addLog("📝 Метадані: включено");
                }

                addLog("\n✅ Відновлення файлів завершено!");

                // ========== ПЕРЕЗАВАНТАЖЕННЯ КОЛЕКЦІЇ ==========
                addLog("⏳ Перезавантаження колекції...");
                collectionManager.switchToCollection(collection);
                addLog("✅ Колекцію перезавантажено");

                // ========== ОНОВЛЕННЯ КЕШІВ ==========
                addLog("🔄 Оновлення кешів...");

                try {
                    // Оновлюємо кеші словників
                    dictionaryCache.loadAuthors(authorRepository.findAll());
                    dictionaryCache.loadGenres(genreRepository.findAll());
                    dictionaryCache.loadSeries(seriesRepository.findAll());
                    dictionaryCache.loadGroups(groupRepository.findAll());
                    addLog("  ✅ Кеші словників оновлено");

                    // Оновлюємо статистику
                    statisticsService.refreshStatistics();
                    addLog("  ✅ Статистику оновлено");

                    // Перебудовуємо Lucene індекс
                    addLog("  ⏳ Перебудова Lucene індексу...");
                    luceneSearchService.rebuildIndex();

                    // Індексуємо всі книги
                    int pageSize = 1000;
                    int offset = 0;
                    int totalIndexed = 0;

                    while (true) {
                        BookQuery query = BookQuery.builder()
                                .pagination(Pagination.of(pageSize, offset))
                                .build();
                        List<Book> books = bookQueryRepository.find(query);
                        if (books.isEmpty()) {
                            break;
                        }
                        luceneSearchService.indexAll(books);
                        totalIndexed += books.size();
                        offset += pageSize;
                    }

                    luceneSearchService.commit();
                    addLog("  ✅ Lucene індекс перебудовано. Проіндексовано " + totalIndexed + " книг");

                } catch (Exception e) {
                    addLog("  ❌ Помилка оновлення кешів: " + e.getMessage());
                    log.error("Помилка оновлення кешів після відновлення", e);
                }

                restoreCompleted = true;

                UiExecutor.runOnUiThread(() -> {
                    statusLabel.setText("✅ Відновлення завершено!");
                    progressBar.setProgress(1.0);
                    progressLabel.setText("100%");
                    restoreButton.setDisable(false);
                    selectPathButton.setDisable(false);
                    cancelButton.setVisible(false);
                    closeButton.setDisable(false);

                    // Оновлюємо статус-бар
                    appState.getStatusBar().setStatistics(statisticsService.getStatistics());
                    appState.getStatusBar().setStatusText("✅ Відновлення завершено! Кеші оновлено.");

                    dialogService.showInfo("Успішно",
                            "✅ Відновлення з резервної копії завершено!\n\n" +
                                    "📁 Папка: " + backupPath + "\n" +
                                    "📚 Проіндексовано книг: " + luceneSearchService.getDocumentCount() + "\n\n" +
                                    "🔄 Колекцію перезавантажено, кеші оновлено.");
                    resetUI();
                });

            } catch (Exception e) {
                log.error("Помилка відновлення", e);
                UiExecutor.runOnUiThread(() -> {
                    statusLabel.setText("❌ Помилка: " + e.getMessage());
                    dialogService.showError("Помилка", "Не вдалося відновити: " + e.getMessage());
                    resetUI();
                });
            }
        }).start();
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Не вдалося видалити: {}", p, e);
                        }
                    });
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
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
        statusLabel.setText("⏳ Скасування...");
        cancelButton.setDisable(true);
    }

    @FXML
    public void onClose() {
        if (stage != null) {
            stage.close();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}