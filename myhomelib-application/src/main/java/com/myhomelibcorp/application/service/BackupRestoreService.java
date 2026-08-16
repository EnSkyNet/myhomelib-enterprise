package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Сервіс для резервного копіювання та відновлення колекцій.
 * Знаходиться в Application шарі, використовує порти для роботи з інфраструктурою.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupRestoreService {

    private final CollectionBackupPort collectionBackupPort;
    private final CollectionStorageManager storageManager;
    private final DictionaryCachePort dictionaryCache;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final GroupRepository groupRepository;
    private final StatisticsService statisticsService;
    private final IndexRebuilder indexRebuilder;

    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;

    /**
     * Створює резервну копію поточної колекції.
     */
    public BackupResult backup(BackupOptions options) throws IOException {
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) {
            return new BackupResult(0, 1, "No active collection");
        }

        Path backupDir = options.backupDir();
        log.info("Starting backup for collection: {} to {}", collection.getName(), backupDir);

        List<BackupItem> items = new ArrayList<>();
        long totalSize = 0;

        // 1. База даних
        String dbPath = collectionBackupPort.getDatabasePath(collection);
        Path dbFile = Paths.get(dbPath);
        if (Files.exists(dbFile)) {
            items.add(new BackupItem(dbFile, backupDir.resolve(dbFile.getFileName().toString())));
            totalSize += Files.size(dbFile);
        }

        // 2. Пошуковий індекс
        if (options.includeIndex()) {
            Path indexDir = findIndexPath(collection);
            if (indexDir != null && Files.exists(indexDir)) {
                items.add(new BackupItem(indexDir, backupDir.resolve("search-index")));
                totalSize += getDirectorySize(indexDir);
            }
        }

        // 3. Кеш обкладинок
        if (options.includeCovers()) {
            Path coversDir = findCoversPath(collection);
            if (coversDir != null && Files.exists(coversDir)) {
                items.add(new BackupItem(coversDir, backupDir.resolve("covers")));
                totalSize += getDirectorySize(coversDir);
            }
        }

        if (items.isEmpty()) {
            return new BackupResult(0, 0, "No data found to backup");
        }

        // Копіюємо файли
        long copiedBytes = 0;
        int copiedItems = 0;
        List<String> errors = new ArrayList<>();

        for (BackupItem item : items) {
            try {
                if (Files.isDirectory(item.source)) {
                    copyDirectory(item.source, item.target, null);
                } else {
                    Files.copy(item.source, item.target, StandardCopyOption.REPLACE_EXISTING);
                    copiedBytes += Files.size(item.source);
                }
                copiedItems++;
            } catch (IOException e) {
                errors.add("Failed to copy " + item.source.getFileName() + ": " + e.getMessage());
                log.error("Failed to copy: {}", item.source, e);
            }
        }

        log.info("Backup completed: {} items, {} bytes", copiedItems, copiedBytes);
        return new BackupResult(copiedItems, errors.size(), errors.isEmpty() ? null : String.join("; ", errors));
    }

    /**
     * Відновлює поточну колекцію з резервної копії.
     */
    public RestoreResult restore(RestoreOptions options) throws Exception {
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) {
            return new RestoreResult(0, "No active collection");
        }

        Path backupDir = options.backupDir();
        log.info("Starting restore for collection: {} from {}", collection.getName(), backupDir);

        // Знаходимо файл бази даних у резервній копії
        Path dbFile = findDbFile(backupDir);
        if (dbFile == null) {
            return new RestoreResult(0, "Database file not found in backup");
        }

        String targetDbPath = collectionBackupPort.getDatabasePath(collection);
        Path targetDb = Paths.get(targetDbPath);
        Files.createDirectories(targetDb.getParent());

        // Відновлюємо базу даних з retry
        boolean dbRestored = false;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Files.deleteIfExists(targetDb);
                Files.copy(dbFile, targetDb, StandardCopyOption.REPLACE_EXISTING);
                dbRestored = true;
                break;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    throw e;
                }
            }
        }

        if (!dbRestored) {
            return new RestoreResult(0, "Failed to restore database after " + MAX_RETRIES + " attempts");
        }

        // Закриваємо колекцію перед відновленням інших файлів
        collectionBackupPort.closeCurrentCollection();

        // Відновлюємо пошуковий індекс
        if (options.restoreIndex()) {
            Path backupIndex = backupDir.resolve("search-index");
            if (Files.exists(backupIndex)) {
                Path targetIndex = findIndexPath(collection);
                if (targetIndex != null) {
                    if (Files.exists(targetIndex)) {
                        deleteDirectory(targetIndex);
                    }
                    copyDirectory(backupIndex, targetIndex, null);
                }
            }
        }

        // Відновлюємо кеш обкладинок
        if (options.restoreCovers()) {
            Path backupCovers = backupDir.resolve("covers");
            if (Files.exists(backupCovers)) {
                Path targetCovers = findCoversPath(collection);
                if (targetCovers != null) {
                    if (Files.exists(targetCovers)) {
                        deleteDirectory(targetCovers);
                    }
                    copyDirectory(backupCovers, targetCovers, null);
                }
            }
        }

        // Оновлюємо кеші та статистику
        refreshCaches();
        statisticsService.refreshStatistics();

        // Перебудовуємо індекс якщо потрібно
        if (options.rebuildIndex()) {
            rebuildIndex();
        }

        log.info("Restore completed successfully");
        return new RestoreResult(1, null);
    }

    // ==================== Допоміжні методи ====================

    private Path findIndexPath(Collection collection) {
        List<String> possiblePaths = List.of(
                System.getProperty("user.home") + "/.myhomelibcorp/search-index-" + collection.getId(),
                System.getProperty("user.home") + "/.myhomelibcorp/search-index",
                System.getProperty("user.dir") + "/search-index-" + collection.getId()
        );
        for (String path : possiblePaths) {
            Path testPath = Paths.get(path);
            if (Files.exists(testPath)) {
                return testPath;
            }
        }
        return null;
    }

    private Path findCoversPath(Collection collection) {
        List<String> possiblePaths = List.of(
                System.getProperty("user.home") + "/.myhomelibcorp/covers/" + collection.getId(),
                System.getProperty("user.home") + "/.myhomelibcorp/covers",
                System.getProperty("user.dir") + "/covers-" + collection.getId()
        );
        for (String path : possiblePaths) {
            Path testPath = Paths.get(path);
            if (Files.exists(testPath)) {
                return testPath;
            }
        }
        return null;
    }

    private Path findDbFile(Path backupDir) throws IOException {
        try (var stream = Files.list(backupDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".db")) {
                    return path;
                }
            }
        }
        return null;
    }

    private void copyDirectory(Path source, Path target, Consumer<Path> fileConsumer) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        try (var stream = Files.walk(source)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
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
                    log.error("Failed to copy: {}", path, e);
                }
            }
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", p, e);
                        }
                    });
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
                        log.warn("Failed to get size: {}", path, e);
                    }
                }
            }
            return size;
        }
    }

    private void refreshCaches() {
        try {
            dictionaryCache.loadAuthors(authorRepository.findAll());
            dictionaryCache.loadGenres(genreRepository.findAll());
            dictionaryCache.loadSeries(seriesRepository.findAll());
            dictionaryCache.loadGroups(groupRepository.findAll());
            log.info("Caches refreshed");
        } catch (Exception e) {
            log.error("Failed to refresh caches", e);
        }
    }

    private void rebuildIndex() {
        try {
            indexRebuilder.rebuildIndex();
            int count = indexRebuilder.getIndexedDocumentCount();
            log.info("Index rebuilt: {} documents", count);
        } catch (Exception e) {
            log.error("Failed to rebuild index", e);
        }
    }

    // ==================== Записи для опцій ====================

    /**
     * Опції резервного копіювання.
     * @param backupDir папка для збереження резервної копії
     * @param includeIndex чи включати пошуковий індекс
     * @param includeCovers чи включати обкладинки
     * @param includeMetadata чи включати метадані
     */
    public record BackupOptions(
            Path backupDir,
            boolean includeIndex,
            boolean includeCovers,
            boolean includeMetadata
    ) {
        public static BackupOptions defaults(Path backupDir) {
            return new BackupOptions(backupDir, true, true, true);
        }
    }

    /**
     * Опції відновлення з резервної копії.
     * @param backupDir папка з резервною копією
     * @param restoreIndex чи відновлювати пошуковий індекс
     * @param restoreCovers чи відновлювати обкладинки
     * @param restoreMetadata чи відновлювати метадані
     * @param rebuildIndex чи перебудовувати індекс після відновлення
     */
    public record RestoreOptions(
            Path backupDir,
            boolean restoreIndex,
            boolean restoreCovers,
            boolean restoreMetadata,
            boolean rebuildIndex
    ) {
        public static RestoreOptions defaults(Path backupDir) {
            return new RestoreOptions(backupDir, true, true, true, true);
        }
    }

    public record BackupResult(int itemsCopied, int errors, String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    public record RestoreResult(int itemsRestored, String error) {
        public boolean isSuccess() {
            return error == null;
        }
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