package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionBackupAdapter implements CollectionBackupPort {
    private final CollectionManager collectionManager;

    @Override public Collection getCurrentCollection() { return collectionManager.getCurrentCollection(); }

    @Override
    public String getDatabasePath(Collection collection) {
        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isEmpty()) {
            dbPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db";
        }
        return dbPath;
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
}
