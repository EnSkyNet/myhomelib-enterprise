package com.myhomelibcorp.application.service;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
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
    private final CacheInvalidationPort cacheInvalidationPort;
    private final DatabaseMigrationPort databaseMigrationPort;
    private final StatisticsService statisticsService;
    private final IndexRebuilder indexRebuilder;
    private final LibraryOperationCoordinator operationCoordinator;

    /**
     * Створює резервну копію поточної колекції.
     */
    public BackupResult backup(BackupOptions options) throws IOException {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.BACKUP)) {
            return backupLocked(options);
        }
    }

    private BackupResult backupLocked(BackupOptions options) throws IOException {
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
            Path snapshot = backupDir.resolve(name);
            collectionBackupPort.createDatabaseSnapshot(collection, snapshot);
            collectionBackupPort.validateDatabaseFile(snapshot);
            copiedItems++;
        } catch (Exception e) {
            errors.add("Database snapshot failed: " + e.getMessage());
            log.error("Database snapshot failed", e);
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
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.RESTORE)) {
            return restoreLocked(options);
        }
    }

    private RestoreResult restoreLocked(RestoreOptions options) throws Exception {
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) return new RestoreResult(0, "No active collection");

        Path backupDir = options.backupDir();
        Path portable = backupDir.resolve(UserDataTransferPort.FILE_NAME);
        log.info("Starting restore for collection: {} from {}, restoreDatabase={}",
                collection.getName(), backupDir, options.restoreDatabase());

        int restoredItems = 0;
        RestoreSwap swap = null;
        try {
            if (options.restoreDatabase()) {
                Path dbFile = findDbFile(backupDir);
                if (dbFile == null) return new RestoreResult(0, "Database file not found in backup");

                Path targetDb = Paths.get(collectionBackupPort.getDatabasePath(collection));
                Files.createDirectories(targetDb.toAbsolutePath().getParent());
                swap = installDatabaseCandidate(collection, dbFile, targetDb);
                restoredItems++;
            }

            if (options.restoreMetadata()) {
                if (Files.isRegularFile(portable)) {
                    var imported = userDataTransferPort.restoreFrom(portable);
                    restoredItems++;
                    log.info("Portable user data restored: sourceSchema={}, matched={}, unmatched={}, bookmarks={}, memberships={}",
                            imported.sourceSchemaVersion(), imported.matchedBooks(), imported.unmatchedBooks(),
                            imported.bookmarks(), imported.groupMemberships());
                } else if (!options.restoreDatabase()) {
                    return new RestoreResult(restoredItems,
                            "Portable user-data file not found: " + UserDataTransferPort.FILE_NAME);
                } else {
                    log.info("Legacy database-only backup detected; portable user-data file is absent");
                }
            }

            cacheInvalidationPort.invalidateAll();
            statisticsService.refreshStatistics();
            if (options.rebuildIndex()) rebuildIndex();

            if (swap != null) commitDatabaseSwap(swap);
            log.info("Restore completed successfully: {} item(s)", restoredItems);
            return new RestoreResult(restoredItems, null);
        } catch (Exception restoreFailure) {
            if (swap != null) rollbackDatabaseSwap(collection, swap, restoreFailure);
            throw restoreFailure;
        }
    }

    private RestoreSwap installDatabaseCandidate(Collection collection, Path sourceDb, Path targetDb) throws Exception {
        Path stagedDb = targetDb.resolveSibling(targetDb.getFileName() + ".restore.tmp");
        Path previousDb = targetDb.resolveSibling(targetDb.getFileName() + ".restore.previous");
        Files.deleteIfExists(stagedDb);
        Files.copy(sourceDb, stagedDb, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(stagedDb) <= 0) throw new IOException("Staged database is empty");
        // Validate before closing the live collection: corrupt input must never disturb the current DB.
        collectionBackupPort.validateDatabaseFile(stagedDb);

        collectionBackupPort.closeCurrentCollection();
        boolean previousExisted = Files.isRegularFile(targetDb);
        try {
            Files.deleteIfExists(previousDb);
            deleteSqliteSidecars(targetDb);
            if (previousExisted) AtomicFileSupport.moveReplacing(targetDb, previousDb);
            AtomicFileSupport.moveReplacing(stagedDb, targetDb);

            collectionBackupPort.openCollection(collection);
            databaseMigrationPort.migrateCurrentCollection();
            collectionBackupPort.validateDatabaseFile(targetDb);
            return new RestoreSwap(targetDb, previousDb, previousExisted);
        } catch (Exception installFailure) {
            RestoreSwap failedSwap = new RestoreSwap(targetDb, previousDb, previousExisted);
            rollbackDatabaseSwap(collection, failedSwap, installFailure);
            throw installFailure;
        } finally {
            try { Files.deleteIfExists(stagedDb); }
            catch (IOException cleanupError) { log.warn("Cannot delete staged restore file {}", stagedDb, cleanupError); }
        }
    }

    private void commitDatabaseSwap(RestoreSwap swap) {
        try {
            Files.deleteIfExists(swap.previousDb());
            deleteSqliteSidecars(swap.previousDb());
        } catch (IOException cleanupError) {
            // The restored database is already validated and active. Keeping the recovery file is safer
            // than failing a successful restore solely because an old snapshot could not be deleted.
            log.warn("Restore succeeded, but previous database recovery file could not be removed: {}",
                    swap.previousDb(), cleanupError);
        }
    }

    private void rollbackDatabaseSwap(Collection collection, RestoreSwap swap, Exception original) {
        try {
            try { collectionBackupPort.closeCurrentCollection(); }
            catch (RuntimeException closeFailure) { original.addSuppressed(closeFailure); }

            deleteSqliteSidecars(swap.targetDb());
            Files.deleteIfExists(swap.targetDb());
            if (swap.previousExisted() && Files.isRegularFile(swap.previousDb())) {
                AtomicFileSupport.moveReplacing(swap.previousDb(), swap.targetDb());
            }

            collectionBackupPort.openCollection(collection);
            databaseMigrationPort.migrateCurrentCollection();
            if (swap.previousExisted()) collectionBackupPort.validateDatabaseFile(swap.targetDb());
            cacheInvalidationPort.invalidateAll();
            try { statisticsService.refreshStatistics(); }
            catch (RuntimeException statsFailure) { original.addSuppressed(statsFailure); }
            try { rebuildIndex(); }
            catch (RuntimeException indexFailure) { original.addSuppressed(indexFailure); }
            log.warn("Restore failed; previous database was restored successfully");
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            log.error("Restore failed and rollback of the previous database also failed", rollbackFailure);
        }
    }

    private static void deleteSqliteSidecars(Path database) throws IOException {
        if (database == null) return;
        Files.deleteIfExists(Path.of(database.toString() + "-wal"));
        Files.deleteIfExists(Path.of(database.toString() + "-shm"));
    }

    private record RestoreSwap(Path targetDb, Path previousDb, boolean previousExisted) { }

    // ==================== Допоміжні методи ====================

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

    private void rebuildIndex() {
        indexRebuilder.rebuildIndex();
        int count = indexRebuilder.getIndexedDocumentCount();
        log.info("Index rebuilt: {} documents", count);
    }

    // ==================== Записи для опцій ====================

    /**
     * Backup options. The SQLite snapshot is always included; Lucene and cover caches are derived
     * state and are intentionally not archived.
     */
    public record BackupOptions(Path backupDir, boolean includeMetadata) {
        public static BackupOptions defaults(Path backupDir) {
            return new BackupOptions(backupDir, true);
        }
    }

    /**
     * Restore options. Search index is derived and rebuilt after restore instead of copying a live
     * Lucene directory. Cover cache is in-memory and regenerates on demand.
     */
    public record RestoreOptions(
            Path backupDir,
            boolean restoreMetadata,
            boolean rebuildIndex,
            boolean restoreDatabase
    ) {
        public static RestoreOptions defaults(Path backupDir) {
            return new RestoreOptions(backupDir, true, true, true);
        }

        /** Portable transfer onto the currently imported catalogue, matched by LibID. */
        public static RestoreOptions userDataOnly(Path backupDir) {
            return new RestoreOptions(backupDir, true, true, false);
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

}