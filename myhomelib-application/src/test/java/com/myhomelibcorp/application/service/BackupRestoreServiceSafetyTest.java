package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackupRestoreServiceSafetyTest {

    @TempDir
    Path temp;

    @Test
    void corruptStagedBackupIsRejectedBeforeLiveCollectionIsClosed() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup"));
        Files.writeString(backup.resolve("library.db"), "corrupt");
        doThrow(new IOException("quick_check failed")).when(f.backupPort).validateDatabaseFile(any(Path.class));

        assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertEquals("old", Files.readString(f.targetDb));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void migrationFailureRollsBackPreviousDatabase() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup"));
        Files.writeString(backup.resolve("library.db"), "new");
        doThrow(new IllegalStateException("migration failed"))
                .doReturn(0)
                .when(f.migrations).migrateCurrentCollection();

        assertThrows(IllegalStateException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertEquals("old", Files.readString(f.targetDb));
        assertFalse(RestoreRecoveryFiles.isPending(f.targetDb));
        verify(f.backupPort, times(2)).closeCurrentCollection();
        verify(f.backupPort, times(2)).openCollection(f.collection);
        verify(f.backupPort, atLeastOnce()).validateDatabaseFile(f.targetDb);
    }


    @Test
    void ambiguousBackupFolderIsRejectedBeforeClosingLiveCollection() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-ambiguous"));
        Files.writeString(backup.resolve("first.db"), "first");
        Files.writeString(backup.resolve("second.db"), "second");

        IOException error = assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertTrue(error.getMessage().contains("multiple SQLite databases"));
        assertEquals("old", Files.readString(f.targetDb));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void matchingDatabaseNameWinsWhenBackupFolderContainsOtherDatabases() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-multiple-with-match"));
        Files.writeString(backup.resolve("other.db"), "wrong");
        Files.writeString(backup.resolve("current.db"), "right");

        BackupRestoreService.RestoreResult result = f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true));

        assertTrue(result.isSuccess());
        assertEquals("right", Files.readString(f.targetDb));
    }

    @Test
    void successfulRestoreCommitsCandidateAndDeletesRecoveryFile() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup"));
        Files.writeString(backup.resolve("library.db"), "new");

        BackupRestoreService.RestoreResult result = f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true));

        assertTrue(result.isSuccess());
        assertEquals("new", Files.readString(f.targetDb));
        assertFalse(Files.exists(Path.of(f.targetDb + ".restore.previous")));
        assertFalse(RestoreRecoveryFiles.isPending(f.targetDb));
        verify(f.statistics).refreshStatistics();
    }

    private static final class Fixture {
        final CollectionBackupPort backupPort = mock(CollectionBackupPort.class);
        final UserDataTransferPort userData = mock(UserDataTransferPort.class);
        final CacheInvalidationPort cache = mock(CacheInvalidationPort.class);
        final DatabaseMigrationPort migrations = mock(DatabaseMigrationPort.class);
        final StatisticsService statistics = mock(StatisticsService.class);
        final IndexRebuilder index = mock(IndexRebuilder.class);
        final Path targetDb;
        final Collection collection;
        final BackupRestoreService service;

        Fixture(Path temp) throws Exception {
            targetDb = temp.resolve("current.db");
            Files.writeString(targetDb, "old");
            collection = new Collection("c1", "Main", null, targetDb.toString(), 0,
                    null, null, null, null);
            when(backupPort.getCurrentCollection()).thenReturn(collection);
            when(backupPort.getDatabasePath(collection)).thenReturn(targetDb.toString());
            service = new BackupRestoreService(backupPort, userData, cache, migrations,
                    statistics, index, new LibraryOperationCoordinator());
        }
    }
}
