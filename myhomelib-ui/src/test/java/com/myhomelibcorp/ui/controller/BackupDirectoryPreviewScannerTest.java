package com.myhomelibcorp.ui.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class BackupDirectoryPreviewScannerTest {
    @TempDir Path temp;

    @Test
    void emptyDirectoryHasNoRestorableContent() throws Exception {
        var preview = BackupDirectoryPreviewScanner.scan(temp);
        assertThat(preview.databaseCount()).isZero();
        assertThat(preview.databaseNames()).isEmpty();
        assertThat(preview.portableUserData()).isFalse();
        assertThat(preview.searchIndex()).isFalse();
        assertThat(preview.covers()).isFalse();
    }

    @Test
    void scansThousandsOfFilesButRetainsOnlyFirstThirtyTwoDatabaseNames() throws Exception {
        for (int i = 0; i < 2048; i++) Files.writeString(temp.resolve("unrelated-" + i + ".txt"), "x");
        for (int i = 0; i < 48; i++) Files.writeString(temp.resolve("collection-" + i + ".db"), "db");
        Files.writeString(temp.resolve("user-data.json"), "{}");
        Files.createDirectory(temp.resolve("search-index"));
        Files.createDirectory(temp.resolve("covers"));

        var preview = BackupDirectoryPreviewScanner.scan(temp);
        assertThat(preview.databaseCount()).isEqualTo(48);
        assertThat(preview.databaseNames()).hasSize(BackupDirectoryPreviewScanner.MAX_DISPLAYED_DATABASES);
        assertThat(new HashSet<>(preview.databaseNames())).hasSize(BackupDirectoryPreviewScanner.MAX_DISPLAYED_DATABASES);
        assertThat(preview.databaseNames()).allMatch(name -> name.endsWith(".db"));
        assertThat(preview.portableUserData()).isTrue();
        assertThat(preview.searchIndex()).isTrue();
        assertThat(preview.covers()).isTrue();
    }

    @Test
    void directoriesNamedLikeDatabaseAreNotTreatedAsDatabaseFiles() throws Exception {
        Files.createDirectory(temp.resolve("fake.db"));
        Files.writeString(temp.resolve("real.db"), "x");
        var preview = BackupDirectoryPreviewScanner.scan(temp);
        assertThat(preview.databaseCount()).isEqualTo(1);
        assertThat(preview.databaseNames()).containsExactly("real.db");
    }

    @Test
    void missingBackupDirectoryIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                BackupDirectoryPreviewScanner.scan(temp.resolve("missing")))
                .isInstanceOf(java.io.IOException.class);
    }
}
