package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SqliteCollectionStorageManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearDataDirOverride() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void deletePhysicalFilesAlsoRemovesContentIndexStateAndQueueCheckpoint() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        String collectionId = "c-delete";
        Path db = tempDir.resolve("library.db");
        Files.writeString(db, "db");

        Path contentIndex = AppPaths.collectionContentIndexDir(collectionId);
        Files.createDirectories(contentIndex);
        Files.writeString(contentIndex.resolve("segment.bin"), "index");
        Files.createDirectories(AppPaths.collectionContentIndexStateFile(collectionId).getParent());
        Files.writeString(AppPaths.collectionContentIndexStateFile(collectionId), "STALE");
        Files.createDirectories(AppPaths.contentIndexQueueStateFile(collectionId).getParent());
        Files.writeString(AppPaths.contentIndexQueueStateFile(collectionId), "queue");

        Collection collection = new Collection(collectionId, "Delete me", tempDir, db.toString(), 0,
                null, null, null, null);
        SqliteCollectionStorageManager manager = new SqliteCollectionStorageManager(mock(CollectionManager.class));

        manager.deletePhysicalFiles(collection);

        assertThat(db).doesNotExist();
        assertThat(contentIndex).doesNotExist();
        assertThat(AppPaths.collectionContentIndexStateFile(collectionId)).doesNotExist();
        assertThat(AppPaths.contentIndexQueueStateFile(collectionId)).doesNotExist();
    }
    @Test
    void preparedDeletionCanRollbackOwnedDatabase() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        String collectionId = "c-rollback";
        Path db = tempDir.resolve("rollback.db");
        Files.writeString(db, "owned-db-bytes");
        Collection collection = new Collection(collectionId, "Rollback", tempDir, db.toString(), 0,
                null, null, null, null);
        SqliteCollectionStorageManager manager = new SqliteCollectionStorageManager(mock(CollectionManager.class));

        manager.preparePhysicalDeletion(collection);

        assertThat(db).doesNotExist();
        assertThat(AppPaths.collectionDeleteRecoveryMarker(collectionId)).exists();

        manager.rollbackPreparedPhysicalDeletion(collection);

        assertThat(db).hasContent("owned-db-bytes");
        assertThat(AppPaths.collectionDeleteRecoveryMarker(collectionId)).doesNotExist();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(path -> path.getFileName().toString().contains("mhl-collection-delete")))
                    .isEmpty();
        }
    }

    @Test
    void preparedDeletionCommitRemovesOwnedDatabaseAndRecoveryMarker() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        String collectionId = "c-commit";
        Path db = tempDir.resolve("commit.db");
        Files.writeString(db, "owned-db-bytes");
        Collection collection = new Collection(collectionId, "Commit", tempDir, db.toString(), 0,
                null, null, null, null);
        SqliteCollectionStorageManager manager = new SqliteCollectionStorageManager(mock(CollectionManager.class));

        manager.preparePhysicalDeletion(collection);
        manager.commitPreparedPhysicalDeletion(collection);

        assertThat(db).doesNotExist();
        assertThat(AppPaths.collectionDeleteRecoveryMarker(collectionId)).doesNotExist();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.filter(path -> path.getFileName().toString().contains("mhl-collection-delete")))
                    .isEmpty();
        }
    }

}
