package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionFileManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteCollectionFileManager implements CollectionFileManager {

    private final CollectionManager collectionManager;

    @Override
    public void closeIfCurrent(Collection collection) {
        if (collectionManager.getCurrentCollection() != null &&
                collectionManager.getCurrentCollection().getId().equals(collection.getId())) {
            collectionManager.closeCurrentCollection();
            log.info("Closed current collection: {}", collection.getId());
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
                    log.info("Deleted database file: {}", dbPath);
                }
            } catch (IOException e) {
                log.error("Failed to delete database file: {}", dbPath, e);
            }
        }

        // 2. Видалення Lucene індексу
        try {
            Path indexDir = Paths.get(System.getProperty("user.home"),
                    ".myhomelibcorp", "search-index-" + collection.getId());
            if (Files.exists(indexDir)) {
                try (Directory dir = FSDirectory.open(indexDir)) {
                    // Закриваємо директорію (вона видалиться при закритті?)
                }
                deleteDirectory(indexDir);
                log.info("Deleted Lucene index: {}", indexDir);
            }
        } catch (Exception e) {
            log.error("Failed to delete Lucene index", e);
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteDirectory(p);
                    } catch (IOException e) {
                        log.error("Failed to delete file: {}", p, e);
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}