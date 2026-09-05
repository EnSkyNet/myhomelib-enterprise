package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;

/**
 * Filesystem-level recovery performed before a collection database is opened.
 *
 * <p>This intentionally runs before Hikari opens the SQLite file so Windows file locking cannot
 * interfere with recovery. The two supported recovery contracts are:</p>
 * <ul>
 *   <li>{@code .restore.pending + .restore.previous}: a user Restore did not reach its commit point,
 *       therefore the previous database is authoritative and is restored. A previous file without
 *       the pending marker is cleanup residue from an already committed Restore.</li>
 *   <li>catalog update {@code .pending} marker: a validated pre-update checkpoint is authoritative
 *       and replaces any partially updated database.</li>
 * </ul>
 */
@Slf4j
final class CollectionCrashRecovery {
    private CollectionCrashRecovery() { }

    static void recoverBeforeOpen(Collection collection, Path targetDatabase) throws IOException {
        if (collection == null || targetDatabase == null) return;
        recoverInterruptedRestore(targetDatabase);
        recoverInterruptedCatalogUpdate(collection, targetDatabase);
        cleanupAbandonedSwapFiles(targetDatabase);
    }

    private static void recoverInterruptedRestore(Path target) throws IOException {
        Path staged = RestoreRecoveryFiles.staged(target);
        Path previous = RestoreRecoveryFiles.previous(target);
        boolean pending = RestoreRecoveryFiles.isPending(target);

        if (pending) {
            // A previous DB plus the pending marker is an unambiguous interrupted Restore: the
            // operation never reached its commit point, so the previous DB is authoritative.
            if (Files.isRegularFile(previous)) {
                log.warn("Detected interrupted database Restore for {}; rolling back to {}", target, previous);
                restorePreviousDatabase(target, previous);
                Files.deleteIfExists(staged);
                RestoreRecoveryFiles.clearPending(target);
                log.warn("Recovered previous SQLite database after interrupted Restore: {}", target);
                return;
            }

            // Crash after intent was recorded but before the live DB was moved: the target is still
            // the old committed database and the staged candidate can be discarded safely.
            if (Files.isRegularFile(target) && Files.isRegularFile(staged)) {
                validateDatabase(target);
                Files.deleteIfExists(staged);
                RestoreRecoveryFiles.clearPending(target);
                log.warn("Discarded staged Restore candidate; live database was never replaced: {}", target);
                return;
            }

            // First-time restore has no previous DB to roll back to. If the only durable candidate
            // is staged, validate and promote it. If it was already promoted before the crash, keep
            // and validate the target. This is the only recoverable database in either case.
            if (!Files.isRegularFile(target) && Files.isRegularFile(staged)) {
                validateDatabase(staged);
                AtomicFileSupport.moveReplacing(staged, target);
                validateDatabase(target);
                RestoreRecoveryFiles.clearPending(target);
                log.warn("Completed interrupted first-time Restore from staged SQLite database: {}", target);
                return;
            }
            if (Files.isRegularFile(target)) {
                validateDatabase(target);
                RestoreRecoveryFiles.clearPending(target);
                log.warn("Accepted validated first-time Restore database after interrupted post-swap work: {}", target);
                return;
            }

            throw new IOException("Restore recovery marker exists but no recoverable database is available for " + target);
        }

        if (Files.isRegularFile(previous)) {
            // No pending marker means Restore crossed its commit point. A leftover previous file can
            // be caused by antivirus/file locking during best-effort cleanup; it must NOT roll back a
            // successfully restored database. Validate the committed target and remove stale state.
            if (Files.isRegularFile(target)) {
                try {
                    validateDatabase(target);
                    Files.deleteIfExists(previous);
                    deleteSqliteSidecars(previous);
                    Files.deleteIfExists(staged);
                    log.info("Removed stale previous Restore database after committed Restore: {}", previous);
                    return;
                } catch (IOException committedTargetInvalid) {
                    // A valid previous DB is a safer last-resort fallback than refusing to open a
                    // corrupt/missing target even though the commit marker is gone.
                    log.error("Committed Restore target is invalid; falling back to stale previous database {}",
                            previous, committedTargetInvalid);
                    restorePreviousDatabase(target, previous);
                    Files.deleteIfExists(staged);
                    return;
                }
            }
            restorePreviousDatabase(target, previous);
            Files.deleteIfExists(staged);
            return;
        }

        // No pending marker and no previous DB: a staged candidate is abandoned unless there is no
        // target at all, in which case it is the only recoverable first-time Restore database.
        if (Files.isRegularFile(staged)) {
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(staged);
            } else {
                validateDatabase(staged);
                AtomicFileSupport.moveReplacing(staged, target);
                validateDatabase(target);
                log.warn("Completed orphaned first-time Restore from staged SQLite database: {}", target);
            }
        }
    }

    private static void restorePreviousDatabase(Path target, Path previous) throws IOException {
        validateDatabase(previous);
        deleteSqliteSidecars(target);
        Files.deleteIfExists(target);
        AtomicFileSupport.moveReplacing(previous, target);
        deleteSqliteSidecars(previous);
        validateDatabase(target);
    }

    private static void recoverInterruptedCatalogUpdate(Collection collection, Path target) throws IOException {
        String collectionId = collection.getId();
        if (collectionId == null || collectionId.isBlank() || !CatalogUpdateRecoveryFiles.isPending(collectionId)) return;

        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
        if (!Files.isRegularFile(checkpoint)) {
            throw new IOException("Catalog update recovery marker exists but checkpoint is missing: " + checkpoint);
        }
        validateDatabase(checkpoint);

        Path staged = sibling(target, ".catalog-crash-recovery.tmp");
        Path failedCurrent = sibling(target, ".catalog-update.crashed-current");
        log.warn("Detected interrupted online catalog update for collection {}; restoring checkpoint {}",
                collectionId, checkpoint);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(staged);
        Files.copy(checkpoint, staged, StandardCopyOption.REPLACE_EXISTING);
        validateDatabase(staged);

        boolean movedCurrent = false;
        try {
            deleteSqliteSidecars(target);
            deleteSqliteSidecars(failedCurrent);
            Files.deleteIfExists(failedCurrent);
            if (Files.isRegularFile(target)) {
                AtomicFileSupport.moveReplacing(target, failedCurrent);
                movedCurrent = true;
            }
            AtomicFileSupport.moveReplacing(staged, target);
            validateDatabase(target);

            Files.deleteIfExists(failedCurrent);
            deleteSqliteSidecars(failedCurrent);
            CatalogUpdateRecoveryFiles.clear(collectionId);
            log.warn("Recovered collection {} to its pre-update SQLite checkpoint", collectionId);
        } catch (Exception recoveryFailure) {
            IOException failure = recoveryFailure instanceof IOException io
                    ? io : new IOException("Cannot recover interrupted online catalog update", recoveryFailure);
            try {
                if (!Files.isRegularFile(target) && movedCurrent && Files.isRegularFile(failedCurrent)) {
                    AtomicFileSupport.moveReplacing(failedCurrent, target);
                }
            } catch (Exception fallbackFailure) {
                failure.addSuppressed(fallbackFailure);
            }
            // Do not clear the marker/checkpoint. The next start must retry recovery rather than
            // silently accepting a partially updated catalog.
            throw failure;
        } finally {
            try { Files.deleteIfExists(staged); }
            catch (IOException cleanupFailure) { log.warn("Cannot delete crash-recovery staging file {}", staged, cleanupFailure); }
        }
    }

    private static void cleanupAbandonedSwapFiles(Path target) {
        // These files are never authoritative once no pending marker / restore.previous exists.
        for (Path path : new Path[] {
                sibling(target, ".catalog-rollback.tmp"),
                sibling(target, ".catalog-update.failed-current"),
                sibling(target, ".catalog-crash-recovery.tmp")
        }) {
            try {
                Files.deleteIfExists(path);
                deleteSqliteSidecars(path);
            } catch (IOException e) {
                log.debug("Cannot remove stale SQLite recovery artifact {}: {}", path, e.getMessage());
            }
        }
    }

    private static Path sibling(Path target, String suffix) {
        return target.resolveSibling(target.getFileName() + suffix);
    }

    private static void validateDatabase(Path database) throws IOException {
        if (!Files.isRegularFile(database) || Files.size(database) <= 0L) {
            throw new IOException("SQLite recovery database is missing or empty: " + database);
        }
        String url = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IOException("SQLite quick_check failed for recovery database: " + database);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot validate SQLite recovery database: " + database, e);
        }
    }

    private static void deleteSqliteSidecars(Path database) throws IOException {
        if (database == null) return;
        Files.deleteIfExists(Path.of(database.toString() + "-wal"));
        Files.deleteIfExists(Path.of(database.toString() + "-shm"));
    }
}
