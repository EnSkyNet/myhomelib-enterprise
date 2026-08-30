package com.myhomelibcorp.application.service;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

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
                AtomicFileSupport.moveReplacing(stagedDb, targetDb);
                restoredItems++;

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

        cacheInvalidationPort.invalidateAll();
        statisticsService.refreshStatistics();
        if (options.rebuildIndex()) {
            try {
                rebuildIndex();
            } catch (Exception e) {
                log.error("Restore data completed, but search index rebuild failed", e);
                return new RestoreResult(restoredItems,
                        "Data restored, but search index rebuild failed: " + e.getMessage());
            }
        }

        log.info("Restore completed successfully: {} item(s)", restoredItems);
        return new RestoreResult(restoredItems, null);
    }

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