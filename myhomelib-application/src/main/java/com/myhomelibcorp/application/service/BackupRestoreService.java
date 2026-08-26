package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
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
    private final UserDataTransferPort userDataTransferPort;
    private final DictionaryCachePort dictionaryCache;
    private final DatabaseMigrationPort databaseMigrationPort;
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
        if (collection == null) return new BackupResult(0, 1, "No active collection");

        Path backupDir = options.backupDir();
        Files.createDirectories(backupDir);
        log.info("Starting backup for collection: {} to {}", collection.getName(), backupDir);

        int copiedItems = 0;
        List<String> errors = new ArrayList<>();

        // Always create a consistent SQLite snapshot rather than copying a live WAL database file.
        try {
            Path dbSource = Paths.get(collectionBackupPort.getDatabasePath(collection));
            String name = dbSource.getFileName() == null ? "library.db" : dbSource.getFileName().toString();
            collectionBackupPort.createDatabaseSnapshot(collection, backupDir.resolve(name));
            copiedItems++;
        } catch (Exception e) {
            errors.add("Database snapshot failed: " + e.getMessage());
            log.error("Database snapshot failed", e);
        }

        if (options.includeIndex()) {
            Path indexDir = findIndexPath(collection);
            if (indexDir != null && Files.exists(indexDir)) {
                try { copyDirectory(indexDir, backupDir.resolve("search-index"), null); copiedItems++; }
                catch (Exception e) { errors.add("Search index: " + e.getMessage()); }
            }
        }

        if (options.includeCovers()) {
            Path coversDir = findCoversPath(collection);
            if (coversDir != null && Files.exists(coversDir)) {
                try { copyDirectory(coversDir, backupDir.resolve("covers"), null); copiedItems++; }
                catch (Exception e) { errors.add("Covers: " + e.getMessage()); }
            }
        }

        if (options.includeMetadata()) {
            try {
                var exported = userDataTransferPort.exportTo(backupDir.resolve(UserDataTransferPort.FILE_NAME));
                copiedItems++;
                log.info("Portable user data exported: schema={}, bookRecords={}, bookmarks={}, memberships={}",
                        exported.schemaVersion(), exported.bookRecords(), exported.bookmarks(), exported.groupMemberships());
            } catch (Exception e) {
                errors.add("Portable user data: " + e.getMessage());
                log.error("Portable user-data export failed", e);
            }
        }

        log.info("Backup completed: {} items, {} errors", copiedItems, errors.size());
        return new BackupResult(copiedItems, errors.size(), errors.isEmpty() ? null : String.join("; ", errors));
    }

    /**
     * Відновлює поточну колекцію з резервної копії.
     */
    public RestoreResult restore(RestoreOptions options) throws Exception {
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) return new RestoreResult(0, "No active collection");

        Path backupDir = options.backupDir();
        Path portable = backupDir.resolve(UserDataTransferPort.FILE_NAME);
        log.info("Starting restore for collection: {} from {}, restoreDatabase={}",
                collection.getName(), backupDir, options.restoreDatabase());

        int restoredItems = 0;
        if (options.restoreDatabase()) {
            Path dbFile = findDbFile(backupDir);
            if (dbFile == null) return new RestoreResult(0, "Database file not found in backup");

            Path targetDb = Paths.get(collectionBackupPort.getDatabasePath(collection));
            Files.createDirectories(targetDb.toAbsolutePath().getParent());

            // Stage the replacement while the current DB is still open. A failed/corrupt source
            // copy therefore cannot delete the live catalogue. Only the final filesystem swap is
            // performed after SQLite/WAL handles are released.
            Path stagedDb = targetDb.resolveSibling(targetDb.getFileName() + ".restore.tmp");
            Files.deleteIfExists(stagedDb);
            boolean staged = false;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    Files.copy(dbFile, stagedDb, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(stagedDb) <= 0) throw new IOException("Staged database is empty");
                    staged = true;
                    break;
                } catch (IOException e) {
                    Files.deleteIfExists(stagedDb);
                    if (attempt == MAX_RETRIES) throw e;
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
            if (!staged) return new RestoreResult(0, "Failed to stage database after " + MAX_RETRIES + " attempts");

            collectionBackupPort.closeCurrentCollection();
            try {
                try {
                    Files.move(stagedDb, targetDb, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(stagedDb, targetDb, StandardCopyOption.REPLACE_EXISTING);
                }
                restoredItems++;

                if (options.restoreIndex()) {
                    Path backupIndex = backupDir.resolve("search-index");
                    if (Files.exists(backupIndex)) {
                        Path targetIndex = findIndexPath(collection);
                        if (targetIndex != null) {
                            if (Files.exists(targetIndex)) deleteDirectory(targetIndex);
                            copyDirectory(backupIndex, targetIndex, null);
                            restoredItems++;
                        }
                    }
                }

                if (options.restoreCovers()) {
                    Path backupCovers = backupDir.resolve("covers");
                    if (Files.exists(backupCovers)) {
                        Path targetCovers = findCoversPath(collection);
                        if (targetCovers != null) {
                            if (Files.exists(targetCovers)) deleteDirectory(targetCovers);
                            copyDirectory(backupCovers, targetCovers, null);
                            restoredItems++;
                        }
                    }
                }
            } finally {
                try { Files.deleteIfExists(stagedDb); }
                catch (IOException cleanupError) { log.warn("Cannot delete staged restore file {}", stagedDb, cleanupError); }
                // Re-open even when an optional index/cover copy fails, so the desktop is not
                // left with a permanently closed current collection, then apply the normal
                // sequential Flyway chain to older database-only backups.
                collectionBackupPort.openCollection(collection);
                databaseMigrationPort.migrateCurrentCollection();
            }
        }

        if (options.restoreMetadata()) {
            if (Files.isRegularFile(portable)) {
                var imported = userDataTransferPort.restoreFrom(portable);
                restoredItems++;
                log.info("Portable user data restored: sourceSchema={}, matched={}, unmatched={}, bookmarks={}, memberships={}",
                        imported.sourceSchemaVersion(), imported.matchedBooks(), imported.unmatchedBooks(),
                        imported.bookmarks(), imported.groupMemberships());
            } else if (!options.restoreDatabase()) {
                return new RestoreResult(restoredItems, "Portable user-data file not found: " + UserDataTransferPort.FILE_NAME);
            } else {
                log.info("Legacy database-only backup detected; portable user-data file is absent");
            }
        }

        refreshCaches();
        statisticsService.refreshStatistics();
        if (options.rebuildIndex()) rebuildIndex();

        log.info("Restore completed successfully: {} item(s)", restoredItems);
        return new RestoreResult(restoredItems, null);
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
            // Do not materialize all authors after restore; navigation/search query authors lazily.
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
            boolean rebuildIndex,
            boolean restoreDatabase
    ) {
        public static RestoreOptions defaults(Path backupDir) {
            return new RestoreOptions(backupDir, true, true, true, true, true);
        }

        /** Portable transfer onto the currently imported catalogue, matched by LibID. */
        public static RestoreOptions userDataOnly(Path backupDir) {
            return new RestoreOptions(backupDir, false, false, true, true, false);
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