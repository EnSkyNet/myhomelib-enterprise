package com.myhomelibcorp.startup;

import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupStartupTaskTest {
    @TempDir Path temp;
    private String previousDataDir;

    @AfterEach
    void restoreProperty() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void removesOnlyUnpublishedSnapshotTempsFromDefaultBackupTree() throws Exception {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.toString());
        Path backup = AppPaths.backupsDir().resolve("MyHomeLib_Backup_1");
        Files.createDirectories(backup);
        Path staged = backup.resolve("library.db.snapshot.tmp");
        Path published = backup.resolve("library.db");
        Files.writeString(staged, "partial");
        Files.writeString(published, "published");

        StartupTaskResult result = new BackupStartupTask()
                .execute(new StartupContext(StartupTestFixtures.collection("c1")));

        assertThat(result.executed()).isTrue();
        assertThat(staged).doesNotExist();
        assertThat(published).exists();
        assertThat(new BackupStartupTask().failurePolicy()).isEqualTo(StartupFailurePolicy.BEST_EFFORT);
    }
}
