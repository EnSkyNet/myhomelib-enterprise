package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

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
    public void createDatabaseSnapshot(Collection collection, Path targetFile) throws IOException {
        if (!collectionManager.hasActiveCollection()) throw new IOException("No active collection for database snapshot");
        Files.createDirectories(targetFile.toAbsolutePath().getParent());
        Files.deleteIfExists(targetFile);
        String quoted = targetFile.toAbsolutePath().normalize().toString().replace("'", "''");
        try {
            collectionManager.getCurrentJdbcTemplate().execute("VACUUM INTO '" + quoted + "'");
        } catch (Exception e) {
            throw new IOException("SQLite VACUUM INTO failed", e);
        }
        if (!Files.isRegularFile(targetFile) || Files.size(targetFile) == 0) {
            throw new IOException("SQLite snapshot was not created: " + targetFile);
        }
        log.info("Created consistent SQLite backup snapshot {}", targetFile);
    }

    @Override
    public void validateDatabaseFile(Path databaseFile) throws IOException {
        if (databaseFile == null || !Files.isRegularFile(databaseFile)) {
            throw new IOException("SQLite database file does not exist: " + databaseFile);
        }
        String url = "jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize();
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IOException("SQLite quick_check failed for " + databaseFile);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot validate SQLite database: " + databaseFile, e);
        }
    }

    @Override
    public void restoreDatabaseSnapshot(Collection collection, Path snapshotFile) throws IOException {
        if (collection == null) throw new IOException("Collection is required for SQLite snapshot restore");
        validateDatabaseFile(snapshotFile);

        Path target = CollectionDatabasePathResolver.resolve(collection);
        Path staged = target.resolveSibling(target.getFileName() + ".catalog-rollback.tmp");
        Path failedCurrent = target.resolveSibling(target.getFileName() + ".catalog-update.failed-current");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(staged);
        Files.copy(snapshotFile, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        validateDatabaseFile(staged);

        boolean targetExisted = Files.isRegularFile(target);
        boolean closed = false;
        try {
            Collection current = collectionManager.getCurrentCollection();
            if (current == null || current.getId() == null || !current.getId().equals(collection.getId())) {
                throw new IOException("Cannot restore snapshot: requested collection is not active");
            }

            collectionManager.closeCurrentCollection();
            closed = true;
            deleteSqliteSidecars(target);
            Files.deleteIfExists(failedCurrent);
            deleteSqliteSidecars(failedCurrent);
            if (targetExisted) AtomicFileSupport.moveReplacing(target, failedCurrent);
            AtomicFileSupport.moveReplacing(staged, target);

            collectionManager.switchToCollection(collection);
            validateDatabaseFile(target);
            Files.deleteIfExists(failedCurrent);
            deleteSqliteSidecars(failedCurrent);
            log.warn("Restored collection {} from pre-update SQLite checkpoint {}", collection.getId(), snapshotFile);
        } catch (Exception restoreFailure) {
            IOException failure = restoreFailure instanceof IOException io
                    ? io : new IOException("Cannot restore SQLite update checkpoint", restoreFailure);
            try {
                if (collectionManager.hasActiveCollection()) collectionManager.closeCurrentCollection();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            try {
                deleteSqliteSidecars(target);
                Files.deleteIfExists(target);
                if (targetExisted && Files.isRegularFile(failedCurrent)) {
                    AtomicFileSupport.moveReplacing(failedCurrent, target);
                }
                if (targetExisted && Files.isRegularFile(target)) {
                    collectionManager.switchToCollection(collection);
                } else if (!targetExisted && closed) {
                    collectionManager.switchToCollection(collection);
                }
            } catch (Exception recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        } finally {
            try { Files.deleteIfExists(staged); }
            catch (IOException cleanupFailure) { log.warn("Cannot delete staged update rollback file {}", staged, cleanupFailure); }
        }
    }

    private static void deleteSqliteSidecars(Path database) throws IOException {
        if (database == null) return;
        Files.deleteIfExists(Path.of(database.toString() + "-wal"));
        Files.deleteIfExists(Path.of(database.toString() + "-shm"));
    }
}
