package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateCollectionDownloadCleanupSecurityTest {

    @TempDir Path temp;
    private String previousDataDir;

    private void configureDataDir() {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
    }

    @AfterEach
    void restoreDataDir() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void normalizedTraversalOutsideCacheIsNeverDeleted() throws Exception {
        configureDataDir();
        Path cache = Files.createDirectories(AppPaths.cacheDir());
        Path outside = cache.getParent().resolve("outside.pkg");
        Files.writeString(outside, "FOREIGN");
        Path traversal = cache.resolve("nested").resolve("..").resolve("..").resolve("outside.pkg");

        UpdateCollectionFromNetworkUseCase.cleanupDownloadedCacheFile(traversal);

        assertThat(outside).exists();
        assertThat(Files.readString(outside)).isEqualTo("FOREIGN");
    }

    @Test
    void symlinkInsideCacheIsRefusedWithoutDeletingTarget() throws Exception {
        configureDataDir();
        Path cache = Files.createDirectories(AppPaths.cacheDir());
        Path foreign = temp.resolve("foreign.pkg");
        Files.writeString(foreign, "FOREIGN");
        Path link = cache.resolve("download.pkg");
        try {
            Files.createSymbolicLink(link, foreign);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported");
        }

        assertThatThrownBy(() -> UpdateCollectionFromNetworkUseCase.cleanupDownloadedCacheFile(link))
                .isInstanceOf(java.io.IOException.class);

        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void ordinaryDownloadedFileInsideCacheIsRemoved() throws Exception {
        configureDataDir();
        Path cache = Files.createDirectories(AppPaths.cacheDir());
        Path downloaded = cache.resolve("download.pkg");
        Files.writeString(downloaded, "owned");

        UpdateCollectionFromNetworkUseCase.cleanupDownloadedCacheFile(downloaded);

        assertThat(downloaded).doesNotExist();
    }
}
