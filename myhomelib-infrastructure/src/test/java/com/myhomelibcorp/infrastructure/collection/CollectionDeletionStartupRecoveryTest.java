package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionDeletionStartupRecoveryTest {
    @TempDir Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void stillRegisteredCollectionRestoresDatabaseFromPendingDeletionTombstone() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        Path db = tempDir.resolve("library.db");
        Files.writeString(db, "database-before-delete");
        Collection collection = new Collection("c-startup-delete", "Library", tempDir, db.toString(), 0,
                null, null, null, null);

        CollectionDeletionRecoveryStore.prepare(collection, db);
        assertThat(db).doesNotExist();
        assertThat(AppPaths.collectionDeleteRecoveryMarker(collection.getId())).exists();

        new CollectionStartupRecoveryService().recoverBeforeOpen(collection);

        assertThat(db).hasContent("database-before-delete");
        assertThat(AppPaths.collectionDeleteRecoveryMarker(collection.getId())).doesNotExist();
    }
}
