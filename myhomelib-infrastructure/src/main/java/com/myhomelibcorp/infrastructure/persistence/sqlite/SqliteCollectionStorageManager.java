package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import com.myhomelibcorp.infrastructure.collection.CollectionDeletionRecoveryStore;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteCollectionStorageManager implements CollectionStorageManager {

    private final CollectionManager collectionManager;

    @Override
    public void closeCollection(Collection collection) {
        if (collectionManager.getCurrentCollection() != null &&
                collectionManager.getCurrentCollection().getId().equals(collection.getId())) {
            collectionManager.closeCurrentCollection();
            log.info("Закрито поточну колекцію: {}", collection.getId());
        }
    }

    @Override
    public void deletePhysicalFiles(Collection collection) {
        Path dbFile = CollectionDatabasePathResolver.resolve(collection);
        deletePrimaryDatabaseOrThrow(dbFile);
        deleteDerivedFiles(collection);
    }

    @Override
    public void deleteDerivedFiles(Collection collection) {
        // Per-collection Lucene index and freshness marker.
        try {
            Path indexDir = AppPaths.collectionSearchIndexDir(collection.getId());
            if (Files.exists(indexDir, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectory(indexDir);
                log.info("Видалено per-collection Lucene індекс: {}", indexDir);
            }
            deleteStateFileBestEffort(AppPaths.collectionSearchIndexStateFile(collection.getId()), "Lucene freshness marker");

            Path legacyIndexDir = AppPaths.dataDir().resolve("search-index-" + collection.getId());
            if (Files.exists(legacyIndexDir, LinkOption.NOFOLLOW_LINKS)) deleteDirectory(legacyIndexDir);
        } catch (Exception e) {
            log.error("Не вдалося видалити per-collection Lucene індекс", e);
        }

        // Full-text content index and state/checkpoint are independently rebuildable.
        try {
            Path contentIndexDir = AppPaths.collectionContentIndexDir(collection.getId());
            if (Files.exists(contentIndexDir, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectory(contentIndexDir);
                log.info("Видалено full-text content index: {}", contentIndexDir);
            }
            deleteStateFileBestEffort(AppPaths.collectionContentIndexStateFile(collection.getId()), "Content-index state marker");
            deleteStateFileBestEffort(AppPaths.contentIndexQueueStateFile(collection.getId()), "Content-index queue checkpoint");
        } catch (Exception e) {
            log.error("Не вдалося видалити full-text content index/state/checkpoint", e);
        }

        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "myhomelib-import-" + collection.getId());
            if (Files.exists(tempDir, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectory(tempDir);
                log.info("Видалено тимчасові файли: {}", tempDir);
            }
        } catch (Exception e) {
            log.error("Не вдалося видалити тимчасові файли", e);
        }
    }

    @Override
    public void preparePhysicalDeletion(Collection collection) {
        Path dbFile = CollectionDatabasePathResolver.resolve(collection);
        try {
            CollectionDeletionRecoveryStore.prepare(collection, dbFile);
            log.info("SQLite БД підготовлено до crash-recoverable видалення: {}", dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося підготувати видалення БД колекції: " + dbFile, e);
        }
    }

    @Override
    public void commitPreparedPhysicalDeletion(Collection collection) {
        Path dbFile = CollectionDatabasePathResolver.resolve(collection);
        try {
            // Registry commit already happened. Derived data is non-authoritative and can be
            // removed before the durable marker is cleared.
            deleteDerivedFiles(collection);
            deleteSidecarBestEffort(Path.of(dbFile.toAbsolutePath().normalize() + "-wal"), "SQLite WAL");
            deleteSidecarBestEffort(Path.of(dbFile.toAbsolutePath().normalize() + "-shm"), "SQLite SHM");
            CollectionDeletionRecoveryStore.commit(collection, dbFile);
            log.info("Crash-recoverable видалення БД завершено: {}", dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("Registry видалено, але cleanup БД колекції не завершено: " + dbFile, e);
        }
    }

    @Override
    public void rollbackPreparedPhysicalDeletion(Collection collection) {
        Path dbFile = CollectionDatabasePathResolver.resolve(collection);
        try {
            CollectionDeletionRecoveryStore.rollback(collection, dbFile);
            log.warn("Підготовлене видалення БД відкочено: {}", dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося відкотити підготовлене видалення БД колекції: " + dbFile, e);
        }
    }

    private void deletePrimaryDatabaseOrThrow(Path dbFile) {
        Path absolute = dbFile.toAbsolutePath().normalize();
        try {
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                Path safe = SafeFilePublication.requireSafeRegularFile(absolute, "Collection database deletion");
                Files.delete(safe);
                log.info("Видалено файл БД: {}", safe);
            }
        } catch (IOException e) {
            log.error("Не вдалося видалити файл БД: {}", absolute, e);
            throw new IllegalStateException("Не вдалося видалити файл БД колекції: " + absolute, e);
        }

        // WAL/SHM do not represent the authoritative registry object once the main DB is gone.
        // Clean them best-effort without turning a successful DB deletion into a false registry rollback.
        deleteSidecarBestEffort(Path.of(absolute + "-wal"), "SQLite WAL");
        deleteSidecarBestEffort(Path.of(absolute + "-shm"), "SQLite SHM");
    }

    private void deleteSidecarBestEffort(Path sidecar, String role) {
        try {
            if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return;
            Path safe = SafeFilePublication.requireSafeRegularFile(sidecar, role + " sidecar");
            Files.delete(safe);
        } catch (IOException e) {
            log.warn("Не вдалося безпечно видалити {} sidecar {}: {}", role, sidecar, e.getMessage());
        }
    }

    private void deleteStateFileBestEffort(Path stateFile, String role) {
        try {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
            Path safe = SafeFilePublication.requireSafeRegularFile(stateFile, role);
            Files.delete(safe);
        } catch (IOException e) {
            log.warn("Не вдалося безпечно видалити {} {}: {}", role, stateFile, e.getMessage());
        }
    }

    @Override
    public void vacuumCurrent() {
        try {
            JdbcTemplate jt = collectionManager.getCurrentJdbcTemplate();
            if (jt != null) {
                jt.execute("VACUUM;");
                log.info("VACUUM виконано для активної колекції");
            }
        } catch (Exception e) {
            log.error("Помилка VACUUM для активної колекції", e);
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Path absolute = path.toAbsolutePath().normalize();
        // Validate the whole root ancestry before the first directory read. A symlink/junction in
        // any parent component would otherwise redirect Files.list() into unrelated user data.
        Path safeRoot = SafeFilePublication.requireSafeDirectoryPath(absolute, "Collection derived-data cleanup");
        deleteDirectoryContents(safeRoot);
        Files.delete(safeRoot);
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path child : stream.toList()) {
                if (Files.isSymbolicLink(child)) {
                    Files.deleteIfExists(child);
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    // Revalidate before traversal so a directory swapped for a link/reparse entry
                    // between listing and recursion is preserved rather than followed.
                    Path safeChild = SafeFilePublication.requireSafeDirectoryPath(
                            child, "Collection derived-data cleanup child");
                    deleteDirectoryContents(safeChild);
                    Files.delete(safeChild);
                    continue;
                }
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    SafeFilePublication.requireSafeRegularFile(child, "Collection derived-data cleanup file");
                    Files.delete(child);
                } else if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing to delete special/reparse entry: " + child);
                }
            }
        }
    }
}