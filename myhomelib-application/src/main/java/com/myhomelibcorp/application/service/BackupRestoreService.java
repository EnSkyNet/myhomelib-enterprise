package com.myhomelibcorp.application.service;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import com.myhomelibcorp.shared.util.Sha256Support;
import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.util.SqliteSidecarSupport;
import com.myhomelibcorp.application.operation.LibraryOperationType;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Сервіс для резервного копіювання та відновлення колекцій.
 * Знаходиться в Application шарі, використовує порти для роботи з інфраструктурою.
 */
@Service
@Slf4j
public class BackupRestoreService {

    private static final String BACKUP_MANIFEST_FILE = "myhomelib-backup.properties";
    private static final String BACKUP_PENDING_FILE = ".myhomelib-backup.pending";
    private static final String BACKUP_MANIFEST_VERSION = "1";
    private static final int MAX_BACKUP_MANIFEST_BYTES = 8 * 1024;

    private final CollectionBackupPort collectionBackupPort;
    private final UserDataTransferPort userDataTransferPort;
    private final CacheInvalidationPort cacheInvalidationPort;
    private final DatabaseMigrationPort databaseMigrationPort;
    private final StatisticsService statisticsService;
    private final IndexRebuilder indexRebuilder;
    private final LibraryOperationCoordinator operationCoordinator;
    private final ContentIndexingQueueService contentIndexingQueue;

    /** Compatibility constructor for focused tests without the background full-text queue. */
    public BackupRestoreService(CollectionBackupPort collectionBackupPort,
                                UserDataTransferPort userDataTransferPort,
                                CacheInvalidationPort cacheInvalidationPort,
                                DatabaseMigrationPort databaseMigrationPort,
                                StatisticsService statisticsService,
                                IndexRebuilder indexRebuilder,
                                LibraryOperationCoordinator operationCoordinator) {
        this(collectionBackupPort, userDataTransferPort, cacheInvalidationPort, databaseMigrationPort,
                statisticsService, indexRebuilder, operationCoordinator, null);
    }

    @Autowired
    public BackupRestoreService(CollectionBackupPort collectionBackupPort,
                                UserDataTransferPort userDataTransferPort,
                                CacheInvalidationPort cacheInvalidationPort,
                                DatabaseMigrationPort databaseMigrationPort,
                                StatisticsService statisticsService,
                                IndexRebuilder indexRebuilder,
                                LibraryOperationCoordinator operationCoordinator,
                                ContentIndexingQueueService contentIndexingQueue) {
        this.collectionBackupPort = collectionBackupPort;
        this.userDataTransferPort = userDataTransferPort;
        this.cacheInvalidationPort = cacheInvalidationPort;
        this.databaseMigrationPort = databaseMigrationPort;
        this.statisticsService = statisticsService;
        this.indexRebuilder = indexRebuilder;
        this.operationCoordinator = operationCoordinator;
        this.contentIndexingQueue = contentIndexingQueue;
    }

    /**
     * Створює резервну копію поточної колекції.
     */
    public BackupResult backup(BackupOptions options) throws IOException {
        return backup(options, null);
    }

    /**
     * Async/UI-safe backup entry point. The expected collection is verified only after the
     * exclusive BACKUP lease has been acquired, closing the click-to-worker SWITCH queue-gap.
     */
    public BackupResult backup(BackupOptions options, String expectedCollectionId) throws IOException {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.BACKUP)) {
            assertExpectedActiveCollection(expectedCollectionId, "backup");
            return backupLocked(options);
        }
    }

    private BackupResult backupLocked(BackupOptions options) throws IOException {
        if (options == null || options.backupDir() == null) {
            throw new IllegalArgumentException("Backup directory is required");
        }
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) return new BackupResult(0, 1, "No active collection");

        Path backupDir = options.backupDir().toAbsolutePath().normalize();
        SafeFilePublication.createDirectoriesNoLinks(backupDir, "Backup directory");
        Path dbSource = Paths.get(collectionBackupPort.getDatabasePath(collection)).toAbsolutePath().normalize();
        String dbName = dbSource.getFileName() == null ? "library.db" : dbSource.getFileName().toString();
        Path snapshot = resolveManifestFile(backupDir, dbName);
        if (snapshot.equals(dbSource) || sameExistingFile(snapshot, dbSource)) {
            return new BackupResult(0, 1, "Backup directory cannot overwrite the active collection database");
        }

        Path pendingMarker = backupDir.resolve(BACKUP_PENDING_FILE);
        AtomicFileSupport.writeStringReplacing(pendingMarker, "backup-in-progress\n", StandardCharsets.UTF_8);
        log.info("Starting backup for collection: {} to {}", collection.getName(), backupDir);

        int copiedItems = 0;
        List<String> errors = new ArrayList<>();
        String dbSha256 = null;
        String userDataSha256 = null;

        // The pending marker is the generation boundary. Restore refuses a directory while this marker
        // exists, so a failed refresh can never silently mix a new database snapshot with stale
        // portable user data from an older backup in the same folder.
        try {
            collectionBackupPort.createDatabaseSnapshot(collection, snapshot);
            dbSha256 = Sha256Support.file(snapshot);
            copiedItems++;
        } catch (Exception e) {
            errors.add("Database snapshot failed: " + safeErrorDetail(e));
            log.error("Database snapshot failed", e);
        }

        if (options.includeMetadata()) {
            try {
                Path userData = backupDir.resolve(UserDataTransferPort.FILE_NAME);
                var exported = userDataTransferPort.exportTo(userData);
                userDataSha256 = Sha256Support.file(userData);
                copiedItems++;
                log.info("Portable user data exported: schema={}, bookRecords={}, bookmarks={}, annotations={}, memberships={}",
                        exported.schemaVersion(), exported.bookRecords(), exported.bookmarks(), exported.annotations(),
                        exported.groupMemberships());
            } catch (Exception e) {
                errors.add("Portable user data: " + safeErrorDetail(e));
                log.error("Portable user-data export failed", e);
            }
        }

        if (errors.isEmpty()) {
            try {
                if (!options.includeMetadata()) {
                    SafeFilePublication.deleteSafeRegularFileIfExists(
                            backupDir.resolve(UserDataTransferPort.FILE_NAME), "Stale portable user data cleanup");
                }
                writeBackupManifest(backupDir, dbName, dbSha256, options.includeMetadata(), userDataSha256);
                SafeFilePublication.deleteSafeRegularFileIfExists(pendingMarker, "Backup pending marker cleanup");
            } catch (Exception e) {
                errors.add("Backup commit failed: " + safeErrorDetail(e));
                log.error("Backup generation could not be committed", e);
            }
        }

        log.info("Backup completed: {} items, {} errors", copiedItems, errors.size());
        return new BackupResult(copiedItems, errors.size(), errors.isEmpty() ? null : String.join("; ", errors));
    }

    /**
     * Відновлює поточну колекцію з резервної копії.
     */
    public RestoreResult restore(RestoreOptions options) throws Exception {
        return restore(options, null);
    }

    /**
     * Async/UI-safe restore entry point. The expected collection is verified under the
     * exclusive RESTORE lease before validation, close, swap or any other DB mutation.
     */
    public RestoreResult restore(RestoreOptions options, String expectedCollectionId) throws Exception {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.RESTORE)) {
            assertExpectedActiveCollection(expectedCollectionId, "restore");
            return restoreLocked(options);
        }
    }

    private void assertExpectedActiveCollection(String expectedCollectionId, String operation) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection current = collectionBackupPort.getCurrentCollection();
        String actual = current == null ? null : current.getId();
        if (!expectedCollectionId.equals(actual)) {
            throw new IllegalStateException("Active collection changed before " + operation
                    + ": expected=" + expectedCollectionId + ", actual=" + actual);
        }
    }

    private RestoreResult restoreLocked(RestoreOptions options) throws Exception {
        Collection collection = collectionBackupPort.getCurrentCollection();
        if (collection == null) return new RestoreResult(0, "No active collection");

        if (options == null || options.backupDir() == null) {
            throw new IllegalArgumentException("Restore directory is required");
        }
        Path backupDir = options.backupDir().toAbsolutePath().normalize();
        SafeFilePublication.requireSafeDirectoryPath(backupDir, "Restore backup directory");
        if (Files.exists(backupDir.resolve(BACKUP_PENDING_FILE), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup directory contains an incomplete backup generation");
        }
        BackupManifest manifest = readBackupManifest(backupDir);
        Path portable = backupDir.resolve(UserDataTransferPort.FILE_NAME);
        if (manifest != null && options.restoreMetadata()) {
            if (!manifest.metadataIncluded()) {
                if (!options.restoreDatabase()) {
                    return new RestoreResult(0, "Portable user data was not included in this backup");
                }
                portable = null;
            } else {
                portable = backupDir.resolve(UserDataTransferPort.FILE_NAME);
                verifyManifestFile(portable, manifest.userDataSha256(), "portable user data");
            }
        }
        log.info("Starting restore for collection: {} from {}, restoreDatabase={}",
                collection.getName(), backupDir, options.restoreDatabase());

        int restoredItems = 0;
        RestoreSwap swap = null;
        try {
            if (options.restoreDatabase()) {
                Path targetDb = Paths.get(collectionBackupPort.getDatabasePath(collection));
                Path dbFile;
                if (manifest != null) {
                    dbFile = resolveManifestFile(backupDir, manifest.databaseFile());
                    verifyManifestFile(dbFile, manifest.databaseSha256(), "database snapshot");
                } else {
                    dbFile = findDbFile(backupDir, targetDb.getFileName() == null ? null : targetDb.getFileName().toString());
                }
                if (dbFile == null) return new RestoreResult(0, "Database file not found in backup");
                if (sameExistingFile(dbFile, targetDb)) {
                    throw new IOException("Restore source must not be the active collection database");
                }
                quiesceContentIndexBeforeDatabaseReplacement(collection);
                SafeFilePublication.createDirectoriesNoLinks(targetDb.toAbsolutePath().getParent(), "Restore database parent");
                swap = installDatabaseCandidate(collection, dbFile, targetDb);
                restoredItems++;
            }

            if (options.restoreMetadata() && portable != null) {
                if (Files.exists(portable, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(portable, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Portable user-data file is not a safe regular file: " + portable.getFileName());
                }
                if (Files.isRegularFile(portable, LinkOption.NOFOLLOW_LINKS)) {
                    Path safePortable = SafeFilePublication.requireSafeRegularFile(portable, "Portable user data");
                    var imported = userDataTransferPort.restoreFrom(safePortable);
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
            boolean indexRebuildPending = false;
            if (options.rebuildIndex()) {
                try {
                    rebuildIndex();
                } catch (RuntimeException indexFailure) {
                    // A full database restore is still rollback-capable until commit, so preserve
                    // all-or-nothing semantics there. With portable user-data-only restore the DB
                    // transaction has already committed and the search index is derived state: do
                    // not falsely report that the restored user data was rolled back.
                    if (swap != null || restoredItems == 0) throw indexFailure;
                    indexRebuildPending = true;
                    log.error("User data was restored, but the derived search index could not be rebuilt", indexFailure);
                }
            }

            if (swap != null) commitDatabaseSwap(swap);
            log.info("Restore completed successfully: {} item(s)", restoredItems);
            return new RestoreResult(restoredItems, null, indexRebuildPending);
        } catch (Exception restoreFailure) {
            if (swap != null) rollbackDatabaseSwap(collection, swap, restoreFailure);
            throw restoreFailure;
        }
    }

    private void quiesceContentIndexBeforeDatabaseReplacement(Collection collection) {
        if (contentIndexingQueue == null || collection == null
                || collection.getId() == null || collection.getId().isBlank()) return;
        if (!contentIndexingQueue.quiesceForDatabaseReplacement(collection.getId(), 5_000L)) {
            throw new IllegalStateException(
                    "Не вдалося зупинити background full-text indexing перед заміною SQLite database");
        }
    }

    private RestoreSwap installDatabaseCandidate(Collection collection, Path sourceDb, Path targetDb) throws Exception {
        Path safeSource = SafeFilePublication.requireSafeRegularFile(sourceDb, "Restore source database");
        Path safeTarget = SafeFilePublication.prepareTarget(targetDb, "Restore target database");
        Path stagedDb = RestoreRecoveryFiles.staged(safeTarget);
        Path previousDb = RestoreRecoveryFiles.previous(safeTarget);
        Path pendingMarker = RestoreRecoveryFiles.pending(safeTarget);
        refuseExistingRecoveryPath(stagedDb, "staged Restore database");
        refuseExistingRecoveryPath(previousDb, "previous Restore database");
        refuseExistingRecoveryPath(pendingMarker, "Restore pending marker");

        // The staging file is created before the live database is closed. Validation can fail
        // here (invalid SQLite, failed copy, wrong SHA), so its cleanup must not be scoped
        // only to the subsequent swap block.
        String stagedHash = null;
        try {
            String sourceHash = SafeFilePublication.sha256(safeSource, "Restore source database");
            try (var input = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(input, stagedDb, "Restore staged database");
            }
            stagedHash = SafeFilePublication.sha256(stagedDb, "Restore staged database");
            if (!sourceHash.equalsIgnoreCase(stagedHash)) {
                throw new IOException("Staged Restore database SHA-256 mismatch");
            }
            if (Files.size(stagedDb) <= 0) throw new IOException("Staged database is empty");
            collectionBackupPort.validateDatabaseFile(stagedDb);

            // Durable transaction intent. The marker itself is fresh; an existing marker indicates
            // unresolved recovery state and must never be overwritten by a new Restore.
            RestoreRecoveryFiles.markPending(safeTarget);
            String markerHash = SafeFilePublication.sha256(pendingMarker, "Restore pending marker");

            // Closing the collection may fail before any database file is detached. In that
            // case the transaction has not begun: remove only our own pending marker so that
            // a later restore is not blocked by a stale intent record. Never remove a marker
            // that another process has replaced while close was running.
            try {
                collectionBackupPort.closeCurrentCollection();
            } catch (Exception closeFailure) {
                try {
                    SafeFilePublication.removeIfOwned(pendingMarker, markerHash,
                            "Restore pending marker cleanup after failed close");
                } catch (IOException cleanupFailure) {
                    closeFailure.addSuppressed(cleanupFailure);
                }
                throw closeFailure;
            }
            boolean previousExisted = Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS);
            String previousHash = null;
            String installedHash = null;
            try {
                if (previousExisted) {
                    previousHash = SafeFilePublication.sha256(safeTarget, "Restore previous live database");
                }
                SqliteSidecarSupport.delete(safeTarget);
                if (previousExisted) {
                    SafeFilePublication.moveNoReplace(safeTarget, previousDb, "Restore previous database detach");
                    String detachedHash = SafeFilePublication.sha256(previousDb, "Restore previous database");
                    if (!previousHash.equalsIgnoreCase(detachedHash)) {
                        throw new IOException("Previous Restore database changed while being detached");
                    }
                }
                SafeFilePublication.moveNoReplace(stagedDb, safeTarget, "Restore candidate install");
                installedHash = SafeFilePublication.sha256(safeTarget, "Restore installed candidate");
                if (!stagedHash.equalsIgnoreCase(installedHash)) {
                    throw new IOException("Installed Restore database SHA-256 mismatch");
                }

                collectionBackupPort.openCollection(collection);
                databaseMigrationPort.migrateCurrentCollection();
                collectionBackupPort.validateDatabaseFile(safeTarget);
                return new RestoreSwap(safeTarget, previousDb, pendingMarker, previousExisted,
                        previousHash, installedHash, markerHash, stagedHash);
            } catch (Exception installFailure) {
                RestoreSwap failedSwap = new RestoreSwap(safeTarget, previousDb, pendingMarker, previousExisted,
                        previousHash, installedHash, markerHash, stagedHash);
                rollbackDatabaseSwap(collection, failedSwap, installFailure);
                throw installFailure;
            }
        } finally {
            if (stagedHash != null && Files.exists(stagedDb, LinkOption.NOFOLLOW_LINKS)) {
                try { SafeFilePublication.removeIfOwned(stagedDb, stagedHash, "Restore staged database cleanup"); }
                catch (IOException cleanupError) {
                    log.warn("Cannot safely delete staged Restore file {}; preserving it as evidence", stagedDb, cleanupError);
                }
            }
            // An interrupted copy that has never been fingerprinted is deliberately retained.
            // Deleting it would make assumptions about ownership of an unverified file.
        }
    }

    /**
     * Commits the filesystem Restore transaction. Clearing the pending marker is the commit point.
     * It must succeed before the rollback source is deleted; otherwise the whole Restore is rolled
     * back by the caller instead of being reported as successful with ambiguous recovery state.
     */
    private void commitDatabaseSwap(RestoreSwap swap) throws IOException {
        SafeFilePublication.removeIfOwned(
                swap.pendingMarker(), swap.markerHash(), "Restore pending marker commit");
        try {
            if (swap.previousExisted() && Files.exists(swap.previousDb(), LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.removeIfOwned(
                        swap.previousDb(), swap.previousHash(), "Restore previous database commit cleanup");
            }
            SqliteSidecarSupport.delete(swap.previousDb());
        } catch (IOException cleanupError) {
            // Pending is already gone, therefore a leftover .restore.previous is cleanup evidence
            // from a committed Restore. Never delete it unless ownership is still provable.
            log.warn("Restore committed, but previous database recovery evidence could not be safely removed: {}",
                    swap.previousDb(), cleanupError);
        }
    }

    private void rollbackDatabaseSwap(Collection collection, RestoreSwap swap, Exception original) {
        boolean databaseRollbackCompleted = false;
        try {
            try { collectionBackupPort.closeCurrentCollection(); }
            catch (RuntimeException closeFailure) { original.addSuppressed(closeFailure); }

            if (swap.installedHash() != null && Files.exists(swap.targetDb(), LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.removeIfOwned(
                        swap.targetDb(), swap.installedHash(), "Restore rollback installed database");
                SqliteSidecarSupport.delete(swap.targetDb());
            }
            if (swap.previousExisted()) {
                if (!Files.exists(swap.previousDb(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Restore rollback source is missing: " + swap.previousDb());
                }
                String currentPrevious = SafeFilePublication.sha256(
                        swap.previousDb(), "Restore rollback previous database");
                if (!swap.previousHash().equalsIgnoreCase(currentPrevious)) {
                    throw new IOException("Restore rollback source changed: " + swap.previousDb());
                }
                SafeFilePublication.moveNoReplace(
                        swap.previousDb(), swap.targetDb(), "Restore rollback previous database");
            }

            collectionBackupPort.openCollection(collection);
            databaseMigrationPort.migrateCurrentCollection();
            if (swap.previousExisted()) collectionBackupPort.validateDatabaseFile(swap.targetDb());
            databaseRollbackCompleted = true;
            cacheInvalidationPort.invalidateAll();
            try { statisticsService.refreshStatistics(); }
            catch (RuntimeException statsFailure) { original.addSuppressed(statsFailure); }
            try { rebuildIndex(); }
            catch (RuntimeException indexFailure) { original.addSuppressed(indexFailure); }
            log.warn("Restore failed; previous database was restored successfully");
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            // Rollback failure means we can no longer prove which SQLite generation is live.
            // Fail closed instead of leaving a potentially half-restored DataSource available to
            // subsequent operations. Startup recovery can use the pending marker/evidence later.
            try {
                collectionBackupPort.closeCurrentCollection();
            } catch (RuntimeException failClosedError) {
                original.addSuppressed(failClosedError);
            }
            log.error("Restore failed and rollback of the previous database also failed; current collection was closed fail-closed",
                    rollbackFailure);
        } finally {
            if (databaseRollbackCompleted) {
                try {
                    if (Files.exists(swap.pendingMarker(), LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.removeIfOwned(
                                swap.pendingMarker(), swap.markerHash(), "Restore rollback pending marker cleanup");
                    }
                } catch (IOException markerCleanupFailure) {
                    original.addSuppressed(markerCleanupFailure);
                    log.warn("Previous database is restored, but Restore pending marker remains: {}",
                            swap.pendingMarker(), markerCleanupFailure);
                }
            }
        }
    }



    private record RestoreSwap(Path targetDb, Path previousDb, Path pendingMarker, boolean previousExisted,
                               String previousHash, String installedHash, String markerHash, String stagedHash) { }

    private static void refuseExistingRecoveryPath(Path path, String role) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite existing " + role + ": " + path);
        }
    }

    // ==================== Допоміжні методи ====================

    private void writeBackupManifest(Path backupDir, String databaseFile, String databaseSha256,
                                     boolean metadataIncluded, String userDataSha256) throws IOException {
        if (databaseFile == null || databaseFile.isBlank() || databaseSha256 == null || databaseSha256.isBlank()) {
            throw new IOException("Backup database manifest data is incomplete");
        }
        StringBuilder body = new StringBuilder(256)
                .append("version=").append(BACKUP_MANIFEST_VERSION).append('\n')
                .append("databaseFile=").append(databaseFile).append('\n')
                .append("databaseSha256=").append(databaseSha256.toLowerCase(Locale.ROOT)).append('\n')
                .append("metadataIncluded=").append(metadataIncluded).append('\n');
        if (metadataIncluded) {
            if (userDataSha256 == null || userDataSha256.isBlank()) {
                throw new IOException("Portable user-data manifest hash is missing");
            }
            body.append("userDataFile=").append(UserDataTransferPort.FILE_NAME).append('\n')
                    .append("userDataSha256=").append(userDataSha256.toLowerCase(Locale.ROOT)).append('\n');
        }
        AtomicFileSupport.writeStringReplacing(backupDir.resolve(BACKUP_MANIFEST_FILE), body, StandardCharsets.UTF_8);
    }

    private BackupManifest readBackupManifest(Path backupDir) throws IOException {
        Path file = backupDir.resolve(BACKUP_MANIFEST_FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        file = SafeFilePublication.requireSafeRegularFile(file, "Backup manifest");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
             var reader = new java.io.InputStreamReader(
                     new java.io.ByteArrayInputStream(BoundedIoSupport.readFully(in, MAX_BACKUP_MANIFEST_BYTES)),
                     StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!BACKUP_MANIFEST_VERSION.equals(properties.getProperty("version"))) {
            throw new IOException("Unsupported backup manifest version: " + properties.getProperty("version"));
        }
        String databaseFile = requiredManifestValue(properties, "databaseFile");
        String databaseSha256 = validateSha256(requiredManifestValue(properties, "databaseSha256"), "databaseSha256");
        String metadataValue = requiredManifestValue(properties, "metadataIncluded");
        if (!metadataValue.equals("true") && !metadataValue.equals("false")) {
            throw new IOException("Invalid backup manifest metadataIncluded value");
        }
        boolean metadataIncluded = Boolean.parseBoolean(metadataValue);
        String userDataSha256 = null;
        if (metadataIncluded) {
            String userDataFile = requiredManifestValue(properties, "userDataFile");
            if (!UserDataTransferPort.FILE_NAME.equals(userDataFile)) {
                throw new IOException("Unsupported portable user-data file in backup manifest: " + userDataFile);
            }
            userDataSha256 = validateSha256(requiredManifestValue(properties, "userDataSha256"), "userDataSha256");
        }
        resolveManifestFile(backupDir, databaseFile);
        return new BackupManifest(databaseFile, databaseSha256, metadataIncluded, userDataSha256);
    }

    private static String requiredManifestValue(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Backup manifest field is missing: " + key);
        return value.trim();
    }

    private static String validateSha256(String value, String field) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 in backup manifest: " + field);
        return normalized;
    }

    private static Path resolveManifestFile(Path backupDir, String fileName) throws IOException {
        Path name = Path.of(fileName);
        boolean unsafeText = fileName.codePoints().anyMatch(ch -> Character.isISOControl(ch))
                || !fileName.equals(fileName.strip())
                || fileName.equals(".") || fileName.equals("..");
        if (name.isAbsolute() || name.getNameCount() != 1 || fileName.contains("/") || fileName.contains("\\") || unsafeText) {
            throw new IOException("Unsafe file name in backup manifest: " + fileName);
        }
        Path resolved = backupDir.resolve(name).normalize();
        if (!resolved.getParent().equals(backupDir.normalize())) {
            throw new IOException("Backup manifest file escapes backup directory: " + fileName);
        }
        return resolved;
    }

    private static void verifyManifestFile(Path file, String expectedSha256, String label) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup " + label + " file is missing: " + file.getFileName());
        }
        Path safe = SafeFilePublication.requireSafeRegularFile(file, "Backup " + label);
        String actual = SafeFilePublication.sha256(safe, "Backup " + label);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Backup " + label + " SHA-256 mismatch");
        }
    }

    private static boolean sameExistingFile(Path left, Path right) throws IOException {
        if (left == null || right == null || !Files.exists(left, LinkOption.NOFOLLOW_LINKS) || !Files.exists(right, LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.isSameFile(left, right);
    }

    private static String safeErrorDetail(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record BackupManifest(String databaseFile, String databaseSha256,
                                  boolean metadataIncluded, String userDataSha256) { }

    /** Select a preferred or sole database without materializing/sorting the entire folder. */
    static Path findDbFile(Path backupDir, String preferredName) throws IOException {
        SafeFilePublication.requireSafeDirectoryPath(backupDir, "Backup directory scan");
        Path first = null;
        Path second = null;
        long count = 0;
        try (var stream = Files.list(backupDir)) {
            var entries = stream.iterator();
            while (entries.hasNext()) {
                Path candidate = entries.next();
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = candidate.getFileName().toString();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".db")) continue;
                if (preferredName != null && !preferredName.isBlank()
                        && name.equalsIgnoreCase(preferredName)) return candidate;
                if (first == null) first = candidate;
                else if (second == null) second = candidate;
                count++;
            }
        }
        if (count == 0) return null;
        if (count == 1) return first;
        // Error diagnostics must stay bounded, even for backups containing millions of files.
        throw new IOException("Backup contains multiple SQLite databases and none matches the active collection: "
                + count + " .db files (examples: " + first.getFileName() + ", " + second.getFileName() + ")");
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

    public record RestoreResult(int itemsRestored, String error, boolean indexRebuildPending) {
        public RestoreResult(int itemsRestored, String error) {
            this(itemsRestored, error, false);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

}