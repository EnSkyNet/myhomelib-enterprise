package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
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
        // 1. Видалення файлу БД
        Path dbFile = CollectionDatabasePathResolver.resolve(collection);
        try {
            if (Files.exists(dbFile)) {
                Files.delete(dbFile);
                Files.deleteIfExists(Path.of(dbFile + "-wal"));
                Files.deleteIfExists(Path.of(dbFile + "-shm"));
                log.info("Видалено файл БД: {}", dbFile);
            }
        } catch (IOException e) {
            log.error("Не вдалося видалити файл БД: {}", dbFile, e);
        }

        // 2. Видалення активного per-collection Lucene індексу та freshness marker.
        try {
            Path indexDir = AppPaths.collectionSearchIndexDir(collection.getId());
            if (Files.exists(indexDir)) {
                deleteDirectory(indexDir);
                log.info("Видалено per-collection Lucene індекс: {}", indexDir);
            }
            Files.deleteIfExists(AppPaths.collectionSearchIndexStateFile(collection.getId()));

            // Best-effort cleanup of the pre-v7.1 experimental location.
            Path legacyIndexDir = AppPaths.dataDir().resolve("search-index-" + collection.getId());
            if (Files.exists(legacyIndexDir)) deleteDirectory(legacyIndexDir);
        } catch (Exception e) {
            log.error("Не вдалося видалити per-collection Lucene індекс", e);
        }

        // 3. Видалення тимчасових файлів (якщо є)
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "myhomelib-import-" + collection.getId());
            if (Files.exists(tempDir)) {
                deleteDirectory(tempDir);
                log.info("Видалено тимчасові файли: {}", tempDir);
            }
        } catch (Exception e) {
            log.error("Не вдалося видалити тимчасові файли", e);
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
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (IOException e) {
                        log.error("Не вдалося видалити файл: {}", p, e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}