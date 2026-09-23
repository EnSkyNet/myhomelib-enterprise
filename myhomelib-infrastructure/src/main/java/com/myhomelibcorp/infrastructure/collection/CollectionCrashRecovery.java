package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.resource.LocalCopyDeletionRecoveryStore;
import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/**
 * Filesystem-level recovery performed before a collection database is opened.
 *
 * <p>Recovery is deliberately fail-closed: protocol files must be regular no-link files, final
 * database replacement never uses {@code REPLACE_EXISTING}, and unknown stale artifacts are moved
 * aside as evidence rather than blindly deleted.</p>
 */
@Slf4j
final class CollectionCrashRecovery {
    private CollectionCrashRecovery() { }

    static void recoverBeforeOpen(Collection collection, Path targetDatabase) throws IOException {
        if (collection == null || targetDatabase == null) return;
        Path target = targetDatabase.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Collection database has no parent: " + target);
        SafeFilePublication.createDirectoriesNoLinks(parent, "Collection recovery database parent");
        // If Delete Collection crashed after detaching the SQLite file but before the registry
        // commit, the collection is still registered and opening it must restore the owned bytes
        // before any other recovery protocol inspects the live database path.
        CollectionDeletionRecoveryStore.recoverRegisteredCollection(collection, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.requireSafeRegularFile(target, "Collection recovery live database");
        }
        recoverInterruptedRestore(target);
        recoverInterruptedCatalogUpdate(collection, target);
        LocalCopyDeletionRecoveryStore.recoverForDatabase(collection.getId(), target);
        preserveAbandonedSwapFiles(target);
    }

    private static void recoverInterruptedRestore(Path target) throws IOException {
        Path staged = RestoreRecoveryFiles.staged(target);
        Path previous = RestoreRecoveryFiles.previous(target);
        Path marker = RestoreRecoveryFiles.pending(target);
        boolean markerExists = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        if (markerExists) SafeFilePublication.requireSafeRegularFile(marker, "Restore recovery marker");
        String markerHash = markerExists ? SafeFilePublication.sha256(marker, "Restore recovery marker") : null;
        boolean pending = markerExists;

        boolean previousExists = safeRegularExists(previous, "Restore previous database");
        boolean stagedExists = safeRegularExists(staged, "Restore staged database");
        boolean targetExists = safeRegularExists(target, "Restore live database");

        if (pending) {
            if (previousExists) {
                log.warn("Detected interrupted database Restore for {}; rolling back to {}", target, previous);
                restorePreviousDatabase(target, previous);
                preserveArtifact(staged, "Restore staged candidate");
                removeOwnedMarker(marker, markerHash, "Restore recovery marker");
                log.warn("Recovered previous SQLite database after interrupted Restore: {}", target);
                return;
            }

            // Crash after intent was recorded but before the live DB was detached. The live DB is
            // still authoritative. Preserve the staged bytes as evidence instead of deleting an
            // unprovable predictable-path file.
            if (targetExists && stagedExists) {
                validateDatabase(target);
                preserveArtifact(staged, "Restore staged candidate");
                removeOwnedMarker(marker, markerHash, "Restore recovery marker");
                log.warn("Preserved staged Restore candidate; live database was never replaced: {}", target);
                return;
            }

            // First-time restore: the staged candidate (or an already promoted target) is the only
            // recoverable database. Promotion is no-replace and refuses symlink/special paths.
            if (!targetExists && stagedExists) {
                validateDatabase(staged);
                SafeFilePublication.moveNoReplace(staged, target, "First-time Restore crash recovery");
                validateDatabase(target);
                removeOwnedMarker(marker, markerHash, "Restore recovery marker");
                log.warn("Completed interrupted first-time Restore from staged SQLite database: {}", target);
                return;
            }
            if (targetExists) {
                validateDatabase(target);
                removeOwnedMarker(marker, markerHash, "Restore recovery marker");
                log.warn("Accepted validated first-time Restore database after interrupted post-swap work: {}", target);
                return;
            }

            throw new IOException("Restore recovery marker exists but no recoverable database is available for " + target);
        }

        if (previousExists) {
            if (targetExists) {
                try {
                    validateDatabase(target);
                    preserveArtifact(previous, "Stale previous Restore database");
                    preserveArtifact(staged, "Stale staged Restore database");
                    log.info("Preserved stale previous Restore database as recovery evidence: {}", previous);
                    return;
                } catch (IOException committedTargetInvalid) {
                    log.error("Committed Restore target is invalid; falling back to stale previous database {}",
                            previous, committedTargetInvalid);
                    restorePreviousDatabase(target, previous);
                    preserveArtifact(staged, "Stale staged Restore database");
                    return;
                }
            }
            restorePreviousDatabase(target, previous);
            preserveArtifact(staged, "Stale staged Restore database");
            return;
        }

        if (stagedExists) {
            if (targetExists) {
                preserveArtifact(staged, "Orphaned staged Restore database");
            } else {
                validateDatabase(staged);
                SafeFilePublication.moveNoReplace(staged, target, "Orphaned first-time Restore recovery");
                validateDatabase(target);
                log.warn("Completed orphaned first-time Restore from staged SQLite database: {}", target);
            }
        }
    }

    private static void restorePreviousDatabase(Path target, Path previous) throws IOException {
        validateDatabase(previous);
        String previousHash = SafeFilePublication.sha256(previous, "Restore rollback source");
        Path detachedCurrent = null;
        String currentHash = null;
        boolean restored = false;
        IOException failure = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                currentHash = SafeFilePublication.sha256(target, "Restore current database before rollback");
                deleteSqliteSidecars(target);
                detachedCurrent = SafeFilePublication.detachIfOwned(
                        target, currentHash, "Restore current database rollback evidence");
            }
            SafeFilePublication.moveNoReplace(previous, target, "Restore previous database rollback");
            String restoredHash = SafeFilePublication.sha256(target, "Restored previous database");
            if (!previousHash.equalsIgnoreCase(restoredHash)) {
                throw new IOException("Restored previous database SHA-256 mismatch: " + target);
            }
            validateDatabase(target);
            restored = true;
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            if (!restored && detachedCurrent != null
                    && Files.exists(detachedCurrent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    SafeFilePublication.moveNoReplace(detachedCurrent, target,
                            "Restore rollback current-database restore");
                    detachedCurrent = null;
                } catch (IOException rollbackFailure) {
                    if (failure != null) failure.addSuppressed(rollbackFailure);
                }
            }
            if (restored && detachedCurrent != null) {
                // The failed candidate is useful crash evidence. Do not silently destroy bytes that
                // cannot be proven to be disposable across a process boundary.
                log.warn("Preserved replaced Restore candidate as recovery evidence: {}", detachedCurrent);
            }
        }
    }

    private static void recoverInterruptedCatalogUpdate(Collection collection, Path target) throws IOException {
        String collectionId = collection.getId();
        if (collectionId == null || collectionId.isBlank()) return;
        Path marker = CatalogUpdateRecoveryFiles.marker(collectionId);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        SafeFilePublication.requireSafeRegularFile(marker, "Catalog update recovery marker");
        String markerHash = SafeFilePublication.sha256(marker, "Catalog update recovery marker");

        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
        SafeFilePublication.requireSafeRegularFile(checkpoint, "Catalog update recovery checkpoint");
        validateDatabase(checkpoint);
        String checkpointHash = SafeFilePublication.sha256(checkpoint, "Catalog update recovery checkpoint");

        Path parent = target.getParent();
        Path workspace = SafeFilePublication.createPrivateWorkspace(parent,
                ".mhl-catalog-crash-recovery-", "Catalog crash-recovery workspace");
        Path staged = workspace.resolve("checkpoint.db");
        Path detachedCurrent = null;
        String currentHash = null;
        boolean installed = false;
        IOException failure = null;
        log.warn("Detected interrupted online catalog update for collection {}; restoring checkpoint {}",
                collectionId, checkpoint);
        try {
            try (var in = Files.newInputStream(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, staged, "Catalog crash-recovery staged checkpoint");
            }
            String stagedHash = SafeFilePublication.sha256(staged, "Catalog crash-recovery staged checkpoint");
            if (!checkpointHash.equalsIgnoreCase(stagedHash)) {
                throw new IOException("Catalog recovery checkpoint changed while being staged");
            }
            validateDatabase(staged);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                currentHash = SafeFilePublication.sha256(target, "Catalog recovery current database");
                deleteSqliteSidecars(target);
                detachedCurrent = SafeFilePublication.detachIfOwned(
                        target, currentHash, "Catalog recovery current database evidence");
            }
            SafeFilePublication.moveNoReplace(staged, target, "Catalog crash-recovery checkpoint install");
            String installedHash = SafeFilePublication.sha256(target, "Catalog recovered database");
            if (!checkpointHash.equalsIgnoreCase(installedHash)) {
                throw new IOException("Catalog recovered database SHA-256 mismatch");
            }
            validateDatabase(target);
            installed = true;

            removeOwnedMarker(marker, markerHash, "Catalog update recovery marker");
            if (Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    SafeFilePublication.removeIfOwned(
                            checkpoint, checkpointHash, "Catalog update recovery checkpoint cleanup");
                } catch (IOException cleanupFailure) {
                    // Marker removal is the recovery commit point. A leftover checkpoint is inert
                    // without the marker and is safer to preserve than to turn a successful restore
                    // into a new startup failure.
                    log.warn("Catalog recovery committed, but checkpoint evidence could not be safely removed: {}",
                            checkpoint, cleanupFailure);
                }
            }
            if (detachedCurrent != null) {
                log.warn("Preserved interrupted catalog database as recovery evidence: {}", detachedCurrent);
            }
            log.warn("Recovered collection {} to its pre-update SQLite checkpoint", collectionId);
        } catch (Exception recoveryFailure) {
            failure = recoveryFailure instanceof IOException io
                    ? io : new IOException("Cannot recover interrupted online catalog update", recoveryFailure);
            if (!installed && detachedCurrent != null
                    && Files.exists(detachedCurrent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    SafeFilePublication.moveNoReplace(detachedCurrent, target,
                            "Catalog crash-recovery rollback");
                    detachedCurrent = null;
                } catch (IOException fallbackFailure) {
                    failure.addSuppressed(fallbackFailure);
                }
            }
            throw failure;
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (IOException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else log.warn("Cannot clean catalog crash-recovery workspace {}", workspace, cleanupFailure);
            }
        }
    }

    private static boolean safeRegularExists(Path path, String role) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        SafeFilePublication.requireSafeRegularFile(path, role);
        return true;
    }

    private static void removeOwnedMarker(Path marker, String markerHash, String role) throws IOException {
        if (markerHash != null && Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.removeIfOwned(marker, markerHash, role);
        }
    }

    private static void preserveArtifact(Path path, String role) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Path safe = SafeFilePublication.requireSafeRegularFile(path, role);
        Path evidence = safe.resolveSibling("." + safe.getFileName() + ".mhl-recovery-evidence-"
                + UUID.randomUUID() + ".tmp");
        SafeFilePublication.moveNoReplace(safe, evidence, role + " evidence");
        log.warn("Preserved {} at {}", role, evidence);
    }

    private static void preserveAbandonedSwapFiles(Path target) {
        for (Path path : new Path[] {
                sibling(target, ".catalog-rollback.tmp"),
                sibling(target, ".catalog-update.failed-current"),
                sibling(target, ".catalog-crash-recovery.tmp")
        }) {
            try {
                preserveArtifact(path, "Legacy catalog recovery artifact");
            } catch (IOException e) {
                log.warn("Cannot safely preserve stale SQLite recovery artifact {}: {}", path, e.getMessage());
            }
        }
    }

    private static Path sibling(Path target, String suffix) {
        return target.resolveSibling(target.getFileName() + suffix);
    }

    private static void validateDatabase(Path database) throws IOException {
        Path safe = SafeFilePublication.requireSafeRegularFile(database, "SQLite recovery database");
        SafeFilePublication.FileSnapshot before =
                SafeFilePublication.snapshot(safe, "SQLite recovery database before quick_check");
        if (before.size() <= 0L) {
            throw new IOException("SQLite recovery database is missing or empty: " + safe);
        }
        String url = "jdbc:sqlite:" + safe;
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            SafeFilePublication.requireSafeRegularFile(safe, "SQLite recovery database opened path");
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IOException("SQLite quick_check failed for recovery database: " + safe);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot validate SQLite recovery database: " + safe, e);
        }
        SafeFilePublication.FileSnapshot after =
                SafeFilePublication.snapshot(safe, "SQLite recovery database after quick_check");
        if (before.size() != after.size() || !before.sha256().equalsIgnoreCase(after.sha256())) {
            throw new IOException("SQLite recovery database changed while validation was in progress: " + safe);
        }
    }

    private static void deleteSqliteSidecars(Path database) throws IOException {
        if (database == null) return;
        // Deleting a symbolic link itself is non-traversing, but a special/reparse entry is not an
        // acceptable SQLite sidecar. Regular sidecars are application-owned by SQLite.
        for (Path sidecar : new Path[] { Path.of(database + "-wal"), Path.of(database + "-shm") }) {
            if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(sidecar)) {
                Files.delete(sidecar);
                continue;
            }
            if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe SQLite sidecar recovery path: " + sidecar);
            }
            Files.delete(sidecar);
        }
    }
}
