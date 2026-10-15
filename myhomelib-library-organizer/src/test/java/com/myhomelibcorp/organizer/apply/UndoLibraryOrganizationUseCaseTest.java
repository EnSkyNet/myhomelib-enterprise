package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationJournalPort;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoLibraryOrganizationUseCaseTest {
    @TempDir Path tempDir;

    @Test
    void committedMoveRestoresVerifiedTargetBackToSource() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path source = tempDir.resolve("source").resolve("book.fb2");
        Path target = tempDir.resolve("destination").resolve("book.fb2");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "verified-book");
        String hash = sha256(target);

        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, itemId, source, "", target, hash, hash, "MOVE",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        int restored = new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId);

        assertThat(restored).isEqualTo(1);
        assertThat(source).exists();
        assertThat(Files.readString(source)).isEqualTo("verified-book");
        assertThat(target).doesNotExist();
        assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
        assertThat(journal.lastOperationState).isEqualTo(OrganizationOperationState.ROLLED_BACK);
    }

    @Test
    void validatedInPlaceOptimizeKeepsOriginalAndDeletesOnlyRedundantVerifiedBackup() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path source = tempDir.resolve("book.epub");
        Path backup = tempDir.resolve("backup.epub");
        Files.writeString(source, "original-epub");
        Files.copy(source, backup);
        String sourceHash = sha256(source);
        String unpublishedCandidateHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("optimized-candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, itemId, source, "", source, sourceHash, unpublishedCandidateHash, "OPTIMIZE",
                OrganizationItemState.VALIDATED, backup, "BALANCED_EPUB33", Files.size(source), Files.size(source),
                0, 0.0, "{}", "", ""));

        int restored = new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId);

        assertThat(restored).isEqualTo(1);
        assertThat(Files.readString(source)).isEqualTo("original-epub");
        assertThat(backup).doesNotExist();
        assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
    }


    @Test
    void collectionMismatchRefusesRollbackBeforeTouchingFilesOrJournal() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path source = tempDir.resolve("mismatch-source.fb2");
        Path target = tempDir.resolve("mismatch-target.fb2");
        Files.writeString(target, "verified-book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.FAILED, Instant.now(), null, "{}", "collection-A"), new OrganizationJournalItem(
                operationId, itemId, source, "", target, hash, hash, "MOVE",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(
                journal, coordinator, List.of(), lifecycle("collection-B", tempDir.resolve("collection-b")));

        assertThatThrownBy(() -> useCase.undo(operationId, "collection-A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active collection differs");

        assertThat(target).exists();
        assertThat(source).doesNotExist();
        assertThat(journal.lastUpdatedItem).isNull();
        assertThat(journal.lastOperationState).isNull();
        assertThat(coordinator.activeOperation()).isNull();
    }

    @Test
    void restoreRefusesDanglingSymlinkAtOriginalSourcePathWhenPlatformAllowsSymlinks() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path source = tempDir.resolve("restored.fb2");
        Path backup = tempDir.resolve("backup.fb2");
        Files.writeString(backup, "original");
        String hash = sha256(backup);
        try {
            Files.createSymbolicLink(source, tempDir.resolve("missing-target"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }

        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, itemId, source, "", null, hash, hash, "ARCHIVE_ORIGINAL",
                OrganizationItemState.COMMITTED, backup, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(Files.isSymbolicLink(source)).isTrue();
        assertThat(Files.readString(backup)).isEqualTo("original");
    }



    @Test
    void committedMoveRetryAfterRestoringFileOnlyCompletesJournal() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("recovered.fb2");
        Path target = tempDir.resolve("previous-location.fb2");
        Files.writeString(source, "already restored");
        String hash = sha256(source);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThat(new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isEqualTo(1);
        assertThat(Files.readString(source)).isEqualTo("already restored");
        assertThat(target).doesNotExist();
        assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
    }

    @Test
    void committedMoveRefusesForeignExistingSourceWithoutDeletingTheTarget() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = Files.writeString(tempDir.resolve("occupied.fb2"), "foreign book");
        Path target = Files.writeString(tempDir.resolve("owned.fb2"), "original book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(source)).isEqualTo("foreign book");
        assertThat(Files.readString(target)).isEqualTo("original book");
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void committedConversionWithMissingOriginalAndBackupCannotBeReportedAsUndone() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("missing.fb2");
        Path target = tempDir.resolve("missing.epub");
        String sourceHash = sha256(Files.writeString(tempDir.resolve("original-digest-only.fb2"), "original"));
        String targetHash = sha256(Files.writeString(tempDir.resolve("converted-digest-only.epub"), "converted"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", target, sourceHash, targetHash, "CONVERT",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no verified original or backup");
        assertThat(journal.lastUpdatedItem).isNull();
        assertThat(journal.lastOperationState).isNull();
    }

    @Test
    void committedConversionRetryAcceptsVerifiedRestoredOriginal() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = Files.writeString(tempDir.resolve("already-restored.fb2"), "original");
        Path target = tempDir.resolve("already-removed.epub");
        String hash = sha256(source);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", target, hash, hash, "CONVERT",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThat(new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isEqualTo(1);
        assertThat(Files.readString(source)).isEqualTo("original");
        assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
    }

    @Test
    void unknownCommittedActionMustNotBeSilentlyMarkedRolledBack() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = Files.writeString(tempDir.resolve("unknown.fb2"), "preserve");
        String hash = sha256(source);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", null, hash, hash, "UNKNOWN_FUTURE_ACTION",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported organizer rollback action");
        assertThat(Files.readString(source)).isEqualTo("preserve");
        assertThat(journal.lastUpdatedItem).isNull();
    }


    @Test
    void committedConversionPreservesResultWhenOriginalAndBackupAreMissing() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("lost-original.fb2");
        Path converted = Files.writeString(tempDir.resolve("only-copy.epub"), "last surviving book");
        String expectedSource = sha256(Files.writeString(tempDir.resolve("digest-source.fb2"), "lost source"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", converted, expectedSource, sha256(converted), "CONVERT",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no verified original or backup");
        assertThat(Files.readString(converted)).isEqualTo("last surviving book");
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void committedOptimizationPreservesResultIfBackupMissing() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path optimized = Files.writeString(tempDir.resolve("optimized.epub"), "optimized survivor");
        String expectedSource = sha256(Files.writeString(tempDir.resolve("original-digest.epub"), "original"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), optimized, "", optimized, expectedSource, sha256(optimized), "OPTIMIZE",
                OrganizationItemState.COMMITTED, tempDir.resolve("missing-backup.epub"), "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(optimized)).isEqualTo("optimized survivor");
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void committedExtractionRetainsExtractedBookIfArchiveNoLongerMatches() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = Files.writeString(tempDir.resolve("invalid-archive.zip"), "changed archive");
        Path extracted = Files.writeString(tempDir.resolve("extracted.fb2"), "only extracted copy");
        String expectedSource = sha256(Files.writeString(tempDir.resolve("old-archive.zip"), "old archive"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "book.fb2", extracted, expectedSource, sha256(extracted), "EXTRACT",
                OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(extracted)).isEqualTo("only extracted copy");
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void validatedConversionRetainsCandidateIfRecoveryMaterialMissing() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("missing-original.fb2");
        Path converted = Files.writeString(tempDir.resolve("pending.epub"), "valid pending candidate");
        String expectedSource = sha256(Files.writeString(tempDir.resolve("different.fb2"), "original content"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", converted, expectedSource, sha256(converted), "CONVERT",
                OrganizationItemState.VALIDATED, null, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(converted)).isEqualTo("valid pending candidate");
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void corruptedBackupIsRejectedBeforeDeletingConvertedOutput() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("missing-original.fb2");
        Path backup = Files.writeString(tempDir.resolve("corrupt-backup.fb2"), "corrupt");
        Path converted = Files.writeString(tempDir.resolve("surviving.epub"), "valuable converted copy");
        String expectedSource = sha256(Files.writeString(tempDir.resolve("original-hash.fb2"), "original"));
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", converted, expectedSource, sha256(converted), "CONVERT",
                OrganizationItemState.COMMITTED, backup, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup hash mismatch");
        assertThat(Files.readString(converted)).isEqualTo("valuable converted copy");
        assertThat(Files.readString(backup)).isEqualTo("corrupt");
        assertThat(journal.lastUpdatedItem).isNull();
    }


    @Test
    void committedInPlaceOptimizationWithOriginalAlreadyPresentDoesNotDeleteBook() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path sourceAndTarget = Files.writeString(tempDir.resolve("unchanged-original.epub"), "verified original");
        Path redundantBackup = Files.writeString(tempDir.resolve("redundant.epub"), "verified original");
        String hash = sha256(sourceAndTarget);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), sourceAndTarget, "", sourceAndTarget, hash, hash, "OPTIMIZE",
                OrganizationItemState.COMMITTED, redundantBackup, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThat(new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isEqualTo(1);
        assertThat(Files.readString(sourceAndTarget)).isEqualTo("verified original");
        assertThat(redundantBackup).doesNotExist();
        assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
    }

    @Test
    void committedArchiveRetryRecognizesRestoredOriginalAndCleansVerifiedBackup() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = Files.writeString(tempDir.resolve("restored-original.fb2"), "verified original");
        Path backup = Files.writeString(tempDir.resolve("stray-backup.fb2"), "verified original");
        String hash = sha256(source);
        MemoryJournal journal = new MemoryJournal(operation(operationId), new OrganizationJournalItem(
                operationId, UUID.randomUUID(), source, "", null, hash, hash, "ARCHIVE_ORIGINAL",
                OrganizationItemState.COMMITTED, backup, "", 0, 0, 0, 0.0, "{}", "", ""));

        assertThat(new UndoLibraryOrganizationUseCase(journal, new LibraryOperationCoordinator(), List.of())
                .undo(operationId)).isEqualTo(1);
        assertThat(Files.readString(source)).isEqualTo("verified original");
        assertThat(backup).doesNotExist();
    }

    private CollectionLifecyclePort lifecycle(String id, Path root) {
        Collection current = new Collection(id, id, root, "collection.db", 0, null, null, null, null);
        return new CollectionLifecyclePort() {
            @Override public void switchToCollection(Collection collection) { }
            @Override public void closeCurrentCollection() { }
            @Override public Collection getCurrentCollection() { return current; }
            @Override public void updateCurrentCollection(Collection collection) { }
            @Override public boolean hasActiveCollection() { return true; }
            @Override public boolean isCollectionReady() { return true; }
            @Override public long getDatabaseSize() { return 0; }
        };
    }

    private OrganizationJournalOperation operation(UUID operationId) {
        return new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.FAILED, Instant.now(), null, "{}");
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class MemoryJournal implements OrganizationJournalPort {
        private final OrganizationJournalOperation operation;
        private final List<OrganizationJournalItem> items;
        private OrganizationJournalItem lastUpdatedItem;
        private OrganizationOperationState lastOperationState;

        private MemoryJournal(OrganizationJournalOperation operation, OrganizationJournalItem item) {
            this.operation = operation;
            this.items = new ArrayList<>(List.of(item));
        }

        @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items) { }
        @Override public void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson) {
            lastOperationState = state;
        }
        @Override public void updateItem(OrganizationJournalItem item) {
            lastUpdatedItem = item;
            items.replaceAll(existing -> existing.itemId().equals(item.itemId()) ? item : existing);
        }
        @Override public List<OrganizationJournalOperation> incompleteOperations() { return List.of(operation); }
        @Override public List<OrganizationJournalItem> items(UUID operationId) { return List.copyOf(items); }
    }
}
