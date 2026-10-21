package com.myhomelibcorp.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRestoreDbSelectionTest {
    @TempDir Path temp;

    @Test
    void choosesPreferredDatabaseAtEndOfLargeUnsortedBackupDirectory() throws Exception {
        for (int i = 0; i < 384; i++) {
            Files.writeString(temp.resolve("archive-" + i + ".db"), "synthetic");
        }
        Path preferred = Files.writeString(temp.resolve("Preferred.DB"), "active");
        assertThat(BackupRestoreService.findDbFile(temp, "preferred.db")).isEqualTo(preferred);
    }

    @Test
    void findsOnlySafeDatabaseAndIgnoresDirectoryWithDbExtension() throws Exception {
        Files.createDirectory(temp.resolve("fake.db"));
        Path real = Files.writeString(temp.resolve("real.db"), "test");
        assertThat(BackupRestoreService.findDbFile(temp, null)).isEqualTo(real);
        assertThat(BackupRestoreService.findDbFile(temp, "unknown.db")).isEqualTo(real);
    }

    @Test
    void returnsNullWhenNoDatabaseExists() throws Exception {
        Files.writeString(temp.resolve("readme.txt"), "notes");
        assertThat(BackupRestoreService.findDbFile(temp, null)).isNull();
    }

    @Test
    void ambiguousDirectoryErrorIsBoundedAndReportsCount() throws Exception {
        for (int i = 0; i < 384; i++) {
            Files.writeString(temp.resolve("candidate-" + i + ".db"), "synthetic");
        }
        assertThatThrownBy(() -> BackupRestoreService.findDbFile(temp, "missing.db"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("384 .db files")
                .hasMessageContaining("multiple SQLite databases")
                .satisfies(throwable -> assertThat(throwable.getMessage().length()).isLessThan(512));
    }
}
