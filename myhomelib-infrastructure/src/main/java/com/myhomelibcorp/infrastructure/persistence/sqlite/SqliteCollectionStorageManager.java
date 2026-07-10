package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        String dbPath = collection.getDbFile();
        if (dbPath != null && !dbPath.isBlank()) {
            try {
                Path dbFile = Paths.get(dbPath);
                if (Files.exists(dbFile)) {
                    Files.delete(dbFile);
                    log.info("Видалено файл БД: {}", dbPath);
                }
            } catch (IOException e) {
                log.error("Не вдалося видалити файл БД: {}", dbPath, e);
            }
        }

        // 2. Видалення Lucene індексу
        try {
            Path indexDir = Paths.get(System.getProperty("user.home"),
                    ".myhomelibcorp", "search-index-" + collection.getId());
            if (Files.exists(indexDir)) {
                // Закриваємо директорію, якщо вона відкрита
                try (Directory dir = FSDirectory.open(indexDir)) {
                    // просто закриваємо
                }
                deleteDirectory(indexDir);
                log.info("Видалено Lucene індекс: {}", indexDir);
            }
        } catch (Exception e) {
            log.error("Не вдалося видалити Lucene індекс", e);
        }

        // 3. Видалення кешу обкладинок (якщо є)
        try {
            Path coverCacheDir = Paths.get(System.getProperty("user.home"),
                    ".myhomelibcorp", "covers-" + collection.getId());
            if (Files.exists(coverCacheDir)) {
                deleteDirectory(coverCacheDir);
                log.info("Видалено кеш обкладинок: {}", coverCacheDir);
            }
        } catch (Exception e) {
            log.error("Не вдалося видалити кеш обкладинок", e);
        }

        // 4. Видалення тимчасових файлів (якщо є)
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"),
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
    public void vacuum(Collection collection) {
        try {
            JdbcTemplate jt = collectionManager.getCurrentJdbcTemplate();
            if (jt != null) {
                jt.execute("VACUUM;");
                log.info("VACUUM виконано для колекції: {}", collection.getId());
            }
        } catch (Exception e) {
            log.error("Помилка VACUUM для колекції: {}", collection.getId(), e);
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