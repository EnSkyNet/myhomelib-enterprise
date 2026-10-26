package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogUpdateRecoveryFilesTest {

    @TempDir
    Path temp;

    private String previousDataDir;

    @BeforeEach
    void useIsolatedDataDir() {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data").toString());
    }

    @AfterEach
    void restoreDataDir() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void publishesPendingMarkerBeforeMutationAndNeverOverwritesExistingIntent() throws Exception {
        String collectionId = "catalog-marker-durability";
        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
        Path marker = CatalogUpdateRecoveryFiles.marker(collectionId);
        Files.createDirectories(checkpoint.getParent());
        Files.writeString(checkpoint, "synthetic pre-update checkpoint");

        CatalogUpdateRecoveryFiles.markPending(collectionId, "first-update");

        assertThat(CatalogUpdateRecoveryFiles.isPending(collectionId)).isTrue();
        assertThat(Files.readString(marker)).contains("operation=first-update").contains("startedAt=");
        assertThatThrownBy(() -> CatalogUpdateRecoveryFiles.markPending(collectionId, "other-update"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("already exists");
        assertThat(Files.readString(marker)).contains("operation=first-update");
        assertThat(Files.readString(checkpoint)).isEqualTo("synthetic pre-update checkpoint");

        CatalogUpdateRecoveryFiles.clear(collectionId);
        assertThat(CatalogUpdateRecoveryFiles.isPending(collectionId)).isFalse();
        assertThat(checkpoint).doesNotExist();
    }
}
