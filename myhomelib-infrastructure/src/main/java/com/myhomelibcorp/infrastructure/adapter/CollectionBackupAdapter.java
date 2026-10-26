package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.util.SqliteSidecarSupport;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionBackupAdapter implements CollectionBackupPort {
    private final CollectionManager collectionManager;

    @Override public Collection getCurrentCollection() { return collectionManager.getCurrentCollection(); }

    @Override
    public String getDatabasePath(Collection collection) {
        return CollectionDatabasePathResolver.resolve(collection).toString();
    }

    @Override public void closeCurrentCollection() { collectionManager.closeCurrentCollection(); }
    @Override public void openCollection(Collection collection) { collectionManager.switchToCollection(collection); }
    @Override public boolean hasActiveCollection() { return collectionManager.hasActiveCollection(); }

    @Override
    public Optional<Instant> latestBackupTime(Collection collection) {
        if (collection == null) return Optional.empty();
        Path backupRoot = AppPaths.backupsDir();
        if (!Files.isDirectory(backupRoot, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            backupRoot = SafeFilePublication.requireSafeDirectoryPath(backupRoot, "Backup age scan root");
        } catch (IOException unsafe) {
            log.debug("Cannot inspect unsafe backup root {}: {}", backupRoot, unsafe.toString());
            return Optional.empty();
        }

        String databaseName = CollectionDatabasePathResolver.resolve(collection).getFileName() == null
                ? "" : CollectionDatabasePathResolver.resolve(collection).getFileName().toString();
        String maintenancePrefix = "collection-" + sanitize(collection.getId()) + "-";
        try (var paths = Files.walk(backupRoot, 3)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName() == null ? "" : path.getFileName().toString();
                        return (!databaseName.isBlank() && name.equalsIgnoreCase(databaseName))
                                || (name.startsWith(maintenancePrefix) && name.toLowerCase(java.util.Locale.ROOT).endsWith(".db"));
                    })
                    .map(path -> {
                        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(); }
                        catch (IOException ignored) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder());
        } catch (IOException | java.io.UncheckedIOException error) {
            // Files.walk() is lazy: traversal failures can surface from the Stream
            // iterator as UncheckedIOException, not just from Files.walk() itself.
            log.debug("Cannot inspect backup age for collection {}: {}", collection.getId(), error.toString());
            return Optional.empty();
        }
    }

    private static String sanitize(String value) {
        return (value == null ? "" : value).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public void createDatabaseSnapshot(Collection collection, Path targetFile) throws IOException {
        createSnapshot(collection, targetFile, true);
    }

    @Override
    public void createRecoveryCheckpoint(Collection collection, Path targetFile) throws IOException {
        createSnapshot(collection, targetFile, false);
    }

    private void createSnapshot(Collection collection, Path targetFile, boolean thoroughValidation) throws IOException {
        if (!collectionManager.hasActiveCollection()) throw new IOException("No active collection for database snapshot");
        requireSnapshotCollection(collection);
        Path target = targetFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("SQLite snapshot target has no parent: " + target);
        SafeFilePublication.createDirectoriesNoLinks(parent, "SQLite snapshot directory");
        String originalTargetHash = SafeFilePublication.snapshotExistingTarget(target, "SQLite snapshot destination");
        Path workspace = SafeFilePublication.createPrivateWorkspace(parent, ".mhl-snapshot-", "SQLite snapshot workspace");
        Path staged = workspace.resolve("snapshot.db");
        String quoted = staged.toString().replace("'", "''");
        try {
            collectionManager.getCurrentJdbcTemplate().execute("BACKUP main TO '" + quoted + "'");
            // A directly invoked adapter can outlive the collection whose JDBC template was
            // selected. Never publish that snapshot as if it belonged to a different catalogue.
            requireSnapshotCollection(collection);
            SafeFilePublication.requireSafeRegularFile(staged, "SQLite staged snapshot");
            if (thoroughValidation) validateDatabaseFile(staged);
            else validateRecoveryCheckpoint(staged);
            SafeFilePublication.publishReplacingIfUnchanged(
                    staged, target, originalTargetHash, "SQLite snapshot publication");
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("SQLite online backup failed", e);
        } finally {
            try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
            catch (IOException cleanupFailure) { log.debug("Cannot delete snapshot workspace {}", workspace, cleanupFailure); }
        }
        SafeFilePublication.FileSnapshot committedSnapshot =
                SafeFilePublication.snapshot(target, "SQLite snapshot target");
        if (committedSnapshot.size() == 0) {
            throw new IOException("SQLite snapshot was not created: " + target);
        }
        log.info("Created consistent SQLite {} {}", thoroughValidation ? "backup snapshot" : "recovery checkpoint", target);
    }

    /**
     * A database snapshot must correspond to the collection named by the caller. Higher-level
     * UI operations already hold a SWITCH-excluding lease, but the infrastructure port may also
     * be invoked directly (maintenance, integrations and tests). Reject a mismatched session
     * before creating or publishing snapshot bytes.
     */
    private void requireSnapshotCollection(Collection requested) throws IOException {
        if (requested == null || requested.getId() == null || requested.getId().isBlank()) {
            throw new IOException("Database snapshot requires an identified collection");
        }
        Collection active = collectionManager.getCurrentCollection();
        if (active == null || !requested.getId().equals(active.getId())) {
            throw new IOException("Database snapshot requested collection is not active: " + requested.getId());
        }
    }

    /**
     * Fast validation for a transient update checkpoint. SQLite's backup API already guarantees
     * transactional page consistency; here we only prove that a non-empty SQLite file can be
     * opened read-only and that the core catalogue schema is visible. A user-created backup still
     * uses the full PRAGMA quick_check path in validateDatabaseFile.
     */
    private static void validateRecoveryCheckpoint(Path databaseFile) throws IOException {
        if (databaseFile == null) {
            throw new IOException("SQLite recovery checkpoint is missing or empty: null");
        }
        Path safeDatabase = SafeFilePublication.requireSafeRegularFile(databaseFile, "SQLite recovery checkpoint");
        SafeFilePublication.FileSnapshot before =
                SafeFilePublication.snapshot(safeDatabase, "SQLite recovery checkpoint before validation");
        if (before.size() < 100) {
            throw new IOException("SQLite recovery checkpoint is missing or empty: " + databaseFile);
        }
        byte[] header = new byte[16];
        try (var input = Files.newInputStream(safeDatabase, LinkOption.NOFOLLOW_LINKS)) {
            int read = input.readNBytes(header, 0, header.length);
            if (read != header.length || !"SQLite format 3\000".equals(new String(header, java.nio.charset.StandardCharsets.ISO_8859_1))) {
                throw new IOException("SQLite recovery checkpoint has an invalid header: " + databaseFile);
            }
        }
        String url = "jdbc:sqlite:" + safeDatabase.toAbsolutePath().normalize();
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            SafeFilePublication.requireSafeRegularFile(safeDatabase, "SQLite recovery checkpoint opened path");
            statement.execute("PRAGMA query_only=ON");
            try (var result = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='books' LIMIT 1")) {
                if (!result.next()) throw new IOException("SQLite recovery checkpoint does not contain the books table");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot open SQLite recovery checkpoint: " + databaseFile, e);
        }
        requireSameSnapshot(safeDatabase, before, "SQLite recovery checkpoint");
    }

    @Override
    public void validateDatabaseFile(Path databaseFile) throws IOException {
        if (databaseFile == null) {
            throw new IOException("SQLite database file does not exist: null");
        }
        Path safeDatabase = SafeFilePublication.requireSafeRegularFile(databaseFile, "SQLite database validation source");
        SafeFilePublication.FileSnapshot before =
                SafeFilePublication.snapshot(safeDatabase, "SQLite database before integrity_check");
        String url = "jdbc:sqlite:" + safeDatabase.toAbsolutePath().normalize();
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA integrity_check")) {
            SafeFilePublication.requireSafeRegularFile(safeDatabase, "SQLite database opened path");
            // Full integrity_check validates index/table consistency; quick_check does not.
            // A backup selected for rollback must be fully validated before swapping files.
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)) || result.next()) {
                throw new IOException("SQLite integrity_check failed for " + databaseFile);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot validate SQLite database: " + databaseFile, e);
        }
        requireSameSnapshot(safeDatabase, before, "SQLite database validation source");
    }

    private static void requireSameSnapshot(Path file, SafeFilePublication.FileSnapshot before, String role)
            throws IOException {
        SafeFilePublication.FileSnapshot after = SafeFilePublication.snapshot(file, role + " after validation");
        if (before.size() != after.size() || !before.sha256().equalsIgnoreCase(after.sha256())) {
            throw new IOException(role + " changed while SQLite validation was in progress: " + file);
        }
    }

    @Override
    public void restoreDatabaseSnapshot(Collection collection, Path snapshotFile) throws IOException {
        if (collection == null) throw new IOException("Collection is required for SQLite snapshot restore");
        Path safeSnapshot = SafeFilePublication.requireSafeRegularFile(snapshotFile, "SQLite rollback snapshot");
        validateDatabaseFile(safeSnapshot);

        Path target = CollectionDatabasePathResolver.resolve(collection).toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Collection database has no parent: " + target);
        SafeFilePublication.createDirectoriesNoLinks(parent, "SQLite rollback target parent");
        Path workspace = SafeFilePublication.createPrivateWorkspace(parent, ".mhl-catalog-rollback-", "SQLite rollback workspace");
        try {
            Path staged = workspace.resolve("candidate.db");
            String snapshotHash = SafeFilePublication.sha256(safeSnapshot, "SQLite rollback snapshot");
            try (var in = Files.newInputStream(safeSnapshot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, staged, "SQLite rollback candidate");
            }
            String stagedHash = SafeFilePublication.sha256(staged, "SQLite rollback candidate");
            if (!snapshotHash.equalsIgnoreCase(stagedHash)) {
                throw new IOException("SQLite rollback candidate SHA-256 mismatch");
            }
            validateDatabaseFile(staged);

            // Check the active collection before entering the mutation/rollback block.
            // Otherwise a mismatch can wrongly close a different, currently active library.
            Collection current = collectionManager.getCurrentCollection();
            if (current == null || current.getId() == null || !current.getId().equals(collection.getId())) {
                throw new IOException("Cannot restore snapshot: requested collection is not active");
            }
            boolean closed = false;
            Path detachedPrevious = null;
            String previousHash = null;
            boolean installed = false;
            try {
                collectionManager.closeCurrentCollection();
                closed = true;
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    previousHash = SafeFilePublication.sha256(target, "SQLite rollback current database");
                }
                SqliteSidecarSupport.delete(target);
                if (previousHash != null) {
                    detachedPrevious = SafeFilePublication.detachIfOwned(
                            target, previousHash, "SQLite rollback current database");
                }
                SafeFilePublication.moveNoReplace(staged, target, "SQLite rollback candidate install");
                // From this point on a candidate occupies the live pathname. Mark it as
                // installed before hashing so an I/O failure during verification still
                // enters the verified rollback/retained-evidence path.
                installed = true;
                String installedHash = SafeFilePublication.sha256(target, "SQLite rollback installed database");
                if (!stagedHash.equalsIgnoreCase(installedHash)) {
                    throw new IOException("SQLite rollback installed database SHA-256 mismatch");
                }

                collectionManager.switchToCollection(collection);
                validateDatabaseFile(target);
                if (detachedPrevious != null) {
                    try { SafeFilePublication.removeIfOwned(
                            detachedPrevious, previousHash, "SQLite rollback old database cleanup"); }
                    catch (IOException cleanupFailure) {
                        log.warn("Rollback succeeded but old database evidence was preserved: {}", detachedPrevious, cleanupFailure);
                    }
                }
                log.warn("Restored collection {} from pre-update SQLite checkpoint {}", collection.getId(), snapshotFile);
            } catch (Exception restoreFailure) {
                IOException failure = restoreFailure instanceof IOException io
                        ? io : new IOException("Cannot restore SQLite update checkpoint", restoreFailure);
                // closeCurrentCollection may throw before any file is touched. There is
                // nothing to roll back and no reason to close another live connection.
                if (!closed) throw failure;
                try {
                    if (collectionManager.hasActiveCollection()) collectionManager.closeCurrentCollection();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                try {
                    if (installed && Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.removeIfOwned(
                                target, stagedHash, "SQLite rollback failed candidate cleanup");
                        SqliteSidecarSupport.delete(target);
                    }
                    if (detachedPrevious != null) {
                        String detachedHash = SafeFilePublication.sha256(
                                detachedPrevious, "SQLite rollback previous evidence");
                        if (!previousHash.equalsIgnoreCase(detachedHash)) {
                            throw new IOException("SQLite rollback previous evidence changed: " + detachedPrevious);
                        }
                        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("SQLite rollback target was concurrently replaced; previous evidence retained at "
                                    + detachedPrevious);
                        }
                        SafeFilePublication.moveNoReplace(
                                detachedPrevious, target, "SQLite rollback previous database restore");
                    }
                    if ((previousHash != null && Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            || (previousHash == null && closed)) {
                        collectionManager.switchToCollection(collection);
                    }
                } catch (Exception recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
        } finally {
            // Staging and its validation happen before the database swap try/catch.
            // The workspace must also be released if copy/hash/validation fails.
            try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
            catch (IOException cleanupFailure) { log.warn("Cannot delete rollback workspace {}", workspace, cleanupFailure); }
        }
    }


}
