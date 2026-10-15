package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.RestoreRecoveryFiles;
import org.junit.jupiter.api.Assumptions;
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
    void queuedBackupRefusesDifferentActiveCollectionBeforeSnapshot() throws Exception {
        Fixture f = new Fixture(temp);
        Collection other = new Collection("c2", "Other", null, temp.resolve("other.db").toString(), 0,
                null, null, null, null);
        when(f.backupPort.getCurrentCollection()).thenReturn(other);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> f.service.backup(
                new BackupRestoreService.BackupOptions(temp.resolve("backup-stale-origin"), false), "c1"));

        assertTrue(error.getMessage().contains("Active collection changed before backup"));
        verify(f.backupPort, never()).createDatabaseSnapshot(any(), any());
    }

    @Test
    void queuedRestoreRefusesDifferentActiveCollectionBeforeClosingDatabase() throws Exception {
        Fixture f = new Fixture(temp);
        Collection other = new Collection("c2", "Other", null, temp.resolve("other.db").toString(), 0,
                null, null, null, null);
        when(f.backupPort.getCurrentCollection()).thenReturn(other);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(temp.resolve("backup-stale-origin"), false, false, true), "c1"));

        assertTrue(error.getMessage().contains("Active collection changed before restore"));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void backupRefusesToOverwriteActiveDatabase() throws Exception {
        Fixture f = new Fixture(temp);
        Path backupDir = f.targetDb.getParent();

        BackupRestoreService.BackupResult result = f.service.backup(
                new BackupRestoreService.BackupOptions(backupDir, false));

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("cannot overwrite the active collection database"));
        assertEquals("old", Files.readString(f.targetDb));
        verify(f.backupPort, never()).createDatabaseSnapshot(any(), any());
        assertFalse(Files.exists(backupDir.resolve(".myhomelib-backup.pending")));
    }

    @Test
    void failedBackupGenerationLeavesPendingMarkerAndRestoreRejectsItBeforeClosingCollection() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-failed-generation"));
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.writeString(target, "new-db");
            return null;
        }).when(f.backupPort).createDatabaseSnapshot(any(), any(Path.class));
        doThrow(new IOException("portable export failed")).when(f.userData).exportTo(any(Path.class));

        BackupRestoreService.BackupResult backupResult = f.service.backup(
                new BackupRestoreService.BackupOptions(backup, true));
        assertFalse(backupResult.isSuccess());
        assertTrue(Files.exists(backup.resolve(".myhomelib-backup.pending")));

        IOException error = assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, true, false, true)));
        assertTrue(error.getMessage().contains("incomplete backup generation"));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void manifestHashMismatchIsRejectedBeforeLiveCollectionIsClosed() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-hash-mismatch"));
        Files.writeString(backup.resolve("library.db"), "tampered");
        Files.writeString(backup.resolve("myhomelib-backup.properties"), """
                version=1
                databaseFile=library.db
                databaseSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                metadataIncluded=false
                """);

        IOException error = assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
        assertEquals("old", Files.readString(f.targetDb));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void corruptStagedBackupIsRejectedBeforeLiveCollectionIsClosed() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup"));
        Files.writeString(backup.resolve("library.db"), "corrupt");
        doThrow(new IOException("quick_check failed")).when(f.backupPort).validateDatabaseFile(any(Path.class));

        assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertEquals("old", Files.readString(f.targetDb));
        assertFalse(Files.exists(RestoreRecoveryFiles.staged(f.targetDb)),
                "An invalid staged snapshot must not block the next restore attempt");
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void validationFailureBeforeSwapDoesNotPoisonNextRestore() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-retry"));
        Files.writeString(backup.resolve("library.db"), "new");
        doThrow(new IOException("first validation failed")).doNothing().doNothing()
                .when(f.backupPort).validateDatabaseFile(any(Path.class));

        assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));
        assertFalse(Files.exists(RestoreRecoveryFiles.staged(f.targetDb)));
        assertEquals("old", Files.readString(f.targetDb));

        BackupRestoreService.RestoreResult retry = f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true));
        assertTrue(retry.isSuccess());
        assertEquals("new", Files.readString(f.targetDb));
        assertFalse(Files.exists(RestoreRecoveryFiles.staged(f.targetDb)));
    }

    @Test
    void staleRestoreStagingFileIsNeverOverwritten() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-stale-staging"));
        Files.writeString(backup.resolve("library.db"), "new");
        Path staged = RestoreRecoveryFiles.staged(f.targetDb);
        Files.writeString(staged, "FOREIGN STAGING");

        IOException error = assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertTrue(error.getMessage().contains("Refusing to overwrite existing staged Restore database"));
        assertEquals("FOREIGN STAGING", Files.readString(staged));
        assertEquals("old", Files.readString(f.targetDb));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    @Test
    void rollbackPreservesConcurrentlyReplacedLiveDatabaseAndPreviousEvidence() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-concurrent-target"));
        Files.writeString(backup.resolve("library.db"), "new");
        doAnswer(invocation -> {
            Files.writeString(f.targetDb, "FOREIGN REPLACEMENT");
            throw new IllegalStateException("migration failed after replacement");
        }).when(f.migrations).migrateCurrentCollection();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));

        assertEquals("FOREIGN REPLACEMENT", Files.readString(f.targetDb));
        Path previous = RestoreRecoveryFiles.previous(f.targetDb);
        assertTrue(Files.exists(previous));
        assertEquals("old", Files.readString(previous));
        assertTrue(RestoreRecoveryFiles.isPending(f.targetDb));
        assertTrue(error.getSuppressed().length > 0, "failed ownership rollback must be surfaced as suppressed evidence");
        verify(f.backupPort, times(3)).closeCurrentCollection();
    }

    @Test
    void failedCollectionCloseBeforeSwapClearsOwnedMarkerAndAllowsRetry() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-close-retry"));
        Files.writeString(backup.resolve("library.db"), "new");
        doThrow(new IllegalStateException("database handle still busy")).doNothing()
                .when(f.backupPort).closeCurrentCollection();

        IllegalStateException first = assertThrows(IllegalStateException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));
        assertTrue(first.getMessage().contains("database handle still busy"));
        assertEquals("old", Files.readString(f.targetDb));
        assertFalse(RestoreRecoveryFiles.isPending(f.targetDb));
        assertFalse(Files.exists(RestoreRecoveryFiles.previous(f.targetDb)));
        assertFalse(Files.exists(RestoreRecoveryFiles.staged(f.targetDb)));

        BackupRestoreService.RestoreResult retry = f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true));
        assertTrue(retry.isSuccess());
        assertEquals("new", Files.readString(f.targetDb));
    }

    @Test
    void failedCollectionCloseMustNotDeleteReplacedPendingMarker() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-close-marker-race"));
        Files.writeString(backup.resolve("library.db"), "new");
        doAnswer(invocation -> {
            Files.writeString(RestoreRecoveryFiles.pending(f.targetDb), "FOREIGN MARKER");
            throw new IllegalStateException("failed close");
        }).when(f.backupPort).closeCurrentCollection();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true)));
        assertEquals("failed close", error.getMessage());
        assertTrue(error.getSuppressed().length > 0, "marker ownership failure must be visible");
        assertEquals("FOREIGN MARKER", Files.readString(RestoreRecoveryFiles.pending(f.targetDb)));
        assertEquals("old", Files.readString(f.targetDb));
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
    void legacyPortableRestoreRefusesSymlinkBeforeImport() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-symlink-portable"));
        Path outside = temp.resolve("outside-user-data.json");
        Files.writeString(outside, "{\"schemaVersion\":1}");
        Path portable = backup.resolve(UserDataTransferPort.FILE_NAME);
        Assumptions.assumeTrue(canCreateSymlink(portable, outside));

        IOException error = assertThrows(IOException.class, () -> f.service.restore(
                new BackupRestoreService.RestoreOptions(backup, true, false, false)));

        assertTrue(error.getMessage().contains("not a safe regular file"));
        verify(f.userData, never()).restoreFrom(any(Path.class));
        verify(f.backupPort, never()).closeCurrentCollection();
        assertEquals("{\"schemaVersion\":1}", Files.readString(outside));
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


    @Test
    void restoreRefusesDatabaseSwapWhenBackgroundContentIndexingCannotQuiesce() throws Exception {
        Fixture f = new Fixture(temp);
        Path backup = Files.createDirectories(temp.resolve("backup-content-index-busy"));
        Files.writeString(backup.resolve("current.db"), "new");
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        when(queue.quiesceForDatabaseReplacement("c1", 5_000L)).thenReturn(false);
        BackupRestoreService guarded = new BackupRestoreService(
                f.backupPort, f.userData, f.cache, f.migrations, f.statistics, f.index,
                new LibraryOperationCoordinator(), queue);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guarded.restore(
                new BackupRestoreService.RestoreOptions(backup, false, false, true), "c1"));

        assertTrue(error.getMessage().contains("background full-text indexing"));
        verify(f.backupPort, never()).closeCurrentCollection();
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception e) {
            return false;
        }
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
