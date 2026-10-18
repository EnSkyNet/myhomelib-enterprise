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
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    void journalCollectionMustMatchEvenWhenCallerPassesActiveCollectionId() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("source-from-other-collection.fb2");
        Path target = Files.writeString(tempDir.resolve("target-from-other-collection.fb2"), "book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.COMPLETED, Instant.now(), null, "{}", "collection-A"),
                new OrganizationJournalItem(operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                        OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), lifecycle("collection-B", tempDir));

        // Passing collection-B must not override the operation's durable collection-A identity.
        assertThatThrownBy(() -> useCase.undo(operationId, "collection-B"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collection");
        assertThat(Files.readString(target)).isEqualTo("book");
        assertThat(source).doesNotExist();
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void omittedCallerCollectionMustNotBypassDurableJournalCollection() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("source-no-caller-identity.fb2");
        Path target = Files.writeString(tempDir.resolve("target-no-caller-identity.fb2"), "book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.COMPLETED, Instant.now(), null, "{}", "collection-A"),
                new OrganizationJournalItem(operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                        OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), lifecycle("collection-B", tempDir));

        assertThatThrownBy(() -> useCase.undo(operationId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collection");
        assertThat(Files.readString(target)).isEqualTo("book");
        assertThat(source).doesNotExist();
        assertThat(journal.lastUpdatedItem).isNull();
    }

    @Test
    void correctJournalAndActiveCollectionPermitVerifiedRollback() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("matching-source.fb2");
        Path target = Files.writeString(tempDir.resolve("matching-target.fb2"), "book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.COMPLETED, Instant.now(), null, "{}", "collection-A"),
                new OrganizationJournalItem(operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                        OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), lifecycle("collection-A", tempDir));
        assertThat(useCase.undo(operationId, "collection-A")).isEqualTo(1);
        assertThat(Files.readString(source)).isEqualTo("book");
        assertThat(target).doesNotExist();
    }

    @Test
    void unidentifiedHistoricalJournalMustRequireManualReviewWhenCollectionIsActive() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("historical-source.fb2");
        Path target = Files.writeString(tempDir.resolve("historical-target.fb2"), "book");
        String hash = sha256(target);
        MemoryJournal journal = new MemoryJournal(operation(operationId),
                new OrganizationJournalItem(operationId, UUID.randomUUID(), source, "", target, hash, hash, "MOVE",
                        OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", ""));
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), lifecycle("collection-A", tempDir));
        assertThatThrownBy(() -> useCase.undo(operationId, "collection-A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("manual review");
        assertThat(Files.readString(target)).isEqualTo("book");
        assertThat(source).doesNotExist();
        assertThat(journal.lastUpdatedItem).isNull();
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

    @Test
    void collectionSwitchDuringJournalLoadMustAbortBeforeFileMutation() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("switch-at-load-restored.fb2");
        Path target = Files.writeString(tempDir.resolve("switch-at-load-target.fb2"), "book data");
        String digest = sha256(target);
        MemoryJournal journal = new MemoryJournal(operationForCollection(operationId, "collection-A"),
                moveItem(operationId, source, target, digest));
        AtomicReference<String> activeId = new AtomicReference<>("collection-A");
        journal.onItemsRead = () -> activeId.set("collection-B");
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), changingLifecycle(activeId));

        assertThatThrownBy(() -> useCase.undo(operationId, "collection-A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collection");
        assertThat(Files.readString(target)).isEqualTo("book data");
        assertThat(source).doesNotExist();
        assertThat(journal.lastUpdatedItem).isNull();
        assertThat(journal.lastOperationState).isNull();
    }

    @Test
    void switchBetweenJournalItemsStopsUndoAndAllowsVerifiedRetry() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path firstSource = tempDir.resolve("first-restored.fb2");
        Path firstTarget = Files.writeString(tempDir.resolve("first-target.fb2"), "book one");
        Path secondSource = tempDir.resolve("second-restored.fb2");
        Path secondTarget = Files.writeString(tempDir.resolve("second-target.fb2"), "book two");
        MemoryJournal journal = new MemoryJournal(operationForCollection(operationId, "collection-A"),
                moveItem(operationId, firstSource, firstTarget, sha256(firstTarget)),
                moveItem(operationId, secondSource, secondTarget, sha256(secondTarget)));
        AtomicReference<String> activeId = new AtomicReference<>("collection-A");
        // Undo reverses journal order: second item is restored first, then the active collection changes.
        journal.onItemUpdated = () -> activeId.set("collection-B");
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of(), changingLifecycle(activeId));

        assertThatThrownBy(() -> useCase.undo(operationId, "collection-A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collection");
        assertThat(Files.readString(secondSource)).isEqualTo("book two");
        assertThat(secondTarget).doesNotExist();
        assertThat(Files.readString(firstTarget)).isEqualTo("book one");
        assertThat(firstSource).doesNotExist();
        assertThat(journal.lastOperationState).isNull();

        // A new invocation may complete the pending item; already rolled back files stay untouched.
        journal.onItemUpdated = null;
        activeId.set("collection-A");
        assertThat(useCase.undo(operationId, "collection-A")).isEqualTo(1);
        assertThat(Files.readString(firstSource)).isEqualTo("book one");
        assertThat(firstTarget).doesNotExist();
        assertThat(Files.readString(secondSource)).isEqualTo("book two");
        assertThat(journal.lastOperationState).isEqualTo(OrganizationOperationState.ROLLED_BACK);
    }

    @Test
    void concurrentJournalChangeAtConditionalWriteMustNotBeOverwritten() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("cas-original.fb2");
        Path target = Files.writeString(tempDir.resolve("cas-moved.fb2"), "original bytes");
        OrganizationJournalItem initial = moveItem(operationId, source, target, sha256(target));
        MemoryJournal journal = new MemoryJournal(operation(operationId), initial);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        journal.onBeforeConditionalUpdate = () -> {
            if (once.compareAndSet(false, true)) {
                OrganizationJournalItem item = journal.items.get(0);
                journal.items.set(0, new OrganizationJournalItem(item.operationId(), item.itemId(),
                        item.sourcePath(), item.sourceEntry(), tempDir.resolve("other-writer.fb2"),
                        item.sourceSha256(), item.targetSha256(), item.action(), item.state(),
                        item.backupPath(), item.optimizationProfile(), item.sizeBefore(), item.sizeAfter(),
                        item.bytesSaved(), item.percentSaved(), item.optimizationSummaryJson(),
                        item.targetStandard(), item.deviceProfile(), item.compressionBackend(),
                        item.validationStatus(), item.errorCode(), item.errorMessage()));
            }
        };
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(
                journal, new LibraryOperationCoordinator(), List.of());
        assertThatThrownBy(() -> useCase.undo(operationId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conditional item update rejected");
        assertThat(Files.readString(source)).isEqualTo("original bytes");
        assertThat(target).doesNotExist();
        assertThat(journal.items.get(0).targetPath()).isEqualTo(tempDir.resolve("other-writer.fb2"));
        assertThat(journal.items.get(0).state()).isEqualTo(OrganizationItemState.COMMITTED);
        assertThat(journal.lastUpdatedItem).isNull();
        assertThat(journal.lastOperationState).isNull();
    }

    @Test
    void journalMutationBetweenUndoItemsMustStopBeforeUsingStaleFilePaths() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path oldSource = tempDir.resolve("stale-source.fb2");
        Path oldTarget = Files.writeString(tempDir.resolve("stale-target.fb2"), "valuable original");
        Path newTarget = tempDir.resolve("foreign-reassigned-target.fb2");
        Path secondSource = tempDir.resolve("second-source.fb2");
        Path secondTarget = Files.writeString(tempDir.resolve("second-target.fb2"), "second book");
        OrganizationJournalItem first = moveItem(operationId, oldSource, oldTarget, sha256(oldTarget));
        OrganizationJournalItem second = moveItem(operationId, secondSource, secondTarget, sha256(secondTarget));
        MemoryJournal journal = new MemoryJournal(operation(operationId), first, second);
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        journal.onItemUpdated = () -> {
            if (changed.compareAndSet(false, true)) {
                journal.items.set(0, new OrganizationJournalItem(
                        first.operationId(), first.itemId(), first.sourcePath(), first.sourceEntry(), newTarget,
                        first.sourceSha256(), first.targetSha256(), first.action(), first.state(),
                        first.backupPath(), first.optimizationProfile(), first.sizeBefore(), first.sizeAfter(),
                        first.bytesSaved(), first.percentSaved(), first.optimizationSummaryJson(),
                        first.targetStandard(), first.deviceProfile(), first.compressionBackend(),
                        first.validationStatus(), first.errorCode(), first.errorMessage()));
            }
        };
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(
                journal, new LibraryOperationCoordinator(), List.of());

        assertThatThrownBy(() -> useCase.undo(operationId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("journal changed");
        assertThat(Files.readString(oldTarget)).isEqualTo("valuable original");
        assertThat(oldSource).doesNotExist();
        assertThat(Files.readString(secondSource)).isEqualTo("second book");
        assertThat(secondTarget).doesNotExist();
        assertThat(journal.lastOperationState).isNull();

        // Retry only after the authoritative journal has been repaired.
        journal.onItemUpdated = null;
        journal.items.set(0, first);
        assertThat(useCase.undo(operationId)).isEqualTo(1);
        assertThat(Files.readString(oldSource)).isEqualTo("valuable original");
        assertThat(oldTarget).doesNotExist();
        assertThat(Files.readString(secondSource)).isEqualTo("second book");
    }

    @Test
    void addedJournalItemMustPreventFalseOperationRolledBack() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("rollback-source.fb2");
        Path target = Files.writeString(tempDir.resolve("rollback-target.fb2"), "original");
        OrganizationJournalItem initial = moveItem(operationId, source, target, sha256(target));
        MemoryJournal journal = new MemoryJournal(operation(operationId), initial);
        java.util.concurrent.atomic.AtomicBoolean inserted = new java.util.concurrent.atomic.AtomicBoolean();
        journal.onItemUpdated = () -> {
            if (inserted.compareAndSet(false, true)) {
                Path addition = tempDir.resolve("concurrent-new-book.fb2");
                journal.items.add(new OrganizationJournalItem(operationId, UUID.randomUUID(), addition, "",
                        null, initial.sourceSha256(), initial.targetSha256(), "KEEP_AS_IS",
                        OrganizationItemState.SKIPPED, null, "", 0, 0, 0, 0, "{}", "", ""));
            }
        };
        UndoLibraryOrganizationUseCase useCase = new UndoLibraryOrganizationUseCase(
                journal, new LibraryOperationCoordinator(), List.of());
        assertThatThrownBy(() -> useCase.undo(operationId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("journal changed");
        assertThat(Files.readString(source)).isEqualTo("original");
        assertThat(target).doesNotExist();
        assertThat(journal.lastOperationState).isNull();
    }

    @Test
    void directUndoMustRejectCorruptCrashManifestBeforeChangingFiles() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("manifest-source.fb2");
        Path target = Files.writeString(tempDir.resolve("manifest-target.fb2"), "verified-manifest-book");
        String digest = sha256(target);
        MemoryJournal journal = new MemoryJournal(operation(operationId),
                moveItem(operationId, source, target, digest));
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, "{\"schemaVersion\":3,\"operationId\":\"" + operationId + "\"}\nTRUNCATED-FRAME");
            assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId))
                    .isInstanceOf(Exception.class);
            assertThat(Files.readString(target)).isEqualTo("verified-manifest-book");
            assertThat(source).doesNotExist();
            assertThat(journal.lastUpdatedItem).isNull();
            assertThat(journal.lastOperationState).isNull();
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }

    @Test
    void directUndoMustRejectReadableManifestWithDifferentItemIdentity() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("item-identity-source.fb2");
        Path target = Files.writeString(tempDir.resolve("item-identity-target.fb2"), "valuable book");
        String digest = sha256(target);
        MemoryJournal journal = new MemoryJournal(operation(operationId),
                moveItem(operationId, source, target, digest));
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            // This is valid legacy schema-2 JSON; its operation ID and roots match
            // SQLite, but its item identity does not. V27 checked only the former.
            Files.writeString(manifest, legacyManifest(operationId, UUID.randomUUID(), source, target, digest));
            assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manual review");
            assertThat(Files.readString(target)).isEqualTo("valuable book");
            assertThat(source).doesNotExist();
            assertThat(journal.lastUpdatedItem).isNull();
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }

    @Test
    void directUndoRejectsItemHashConflictEvenWhenManifestItemIdMatches() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("mismatched-digest-source.fb2");
        Path target = Files.writeString(tempDir.resolve("mismatched-digest-target.fb2"), "original bytes");
        String digest = sha256(target);
        OrganizationJournalItem dbItem = moveItem(operationId, source, target, digest);
        MemoryJournal journal = new MemoryJournal(operation(operationId), dbItem);
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            String foreignHash = "a".repeat(64);
            Files.writeString(manifest, legacyManifest(operationId, dbItem.itemId(), source, target, foreignHash));
            assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("manual review");
            assertThat(Files.readString(target)).isEqualTo("original bytes");
            assertThat(source).doesNotExist();
            assertThat(journal.lastUpdatedItem).isNull();
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }

    @Test
    void directUndoAcceptsConsistentLegacyManifestAndRestoresVerifiedBook() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("matching-manifest-source.fb2");
        Path target = Files.writeString(tempDir.resolve("matching-manifest-target.fb2"), "original bytes");
        String digest = sha256(target);
        OrganizationJournalItem dbItem = moveItem(operationId, source, target, digest);
        MemoryJournal journal = new MemoryJournal(operation(operationId), dbItem);
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, legacyManifest(operationId, dbItem.itemId(), source, target, digest));
            assertThat(new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId)).isEqualTo(1);
            assertThat(Files.readString(source)).isEqualTo("original bytes");
            assertThat(target).doesNotExist();
            assertThat(journal.lastUpdatedItem.state()).isEqualTo(OrganizationItemState.ROLLED_BACK);
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }


    @Test
    void manifestIdentityChangedDuringUndoMustPreventOperationFinalization() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("v32-source.fb2");
        Path target = Files.writeString(tempDir.resolve("v32-target.fb2"), "verified-content");
        String digest = sha256(target);
        OrganizationJournalItem item = moveItem(operationId, source, target, digest);
        MemoryJournal journal = new MemoryJournal(operation(operationId), item);
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, legacyManifest(operationId, item.itemId(), source, target, digest));
            journal.onItemUpdated = () -> {
                try {
                    Files.writeString(manifest, legacyManifest(operationId, UUID.randomUUID(), source, target, digest));
                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            };
            assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("manual review");
            assertThat(Files.readString(source)).isEqualTo("verified-content");
            assertThat(target).doesNotExist();
            assertThat(journal.lastOperationState).isNull();
            assertThat(manifest).exists();
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }

    @Test
    void manifestRemovedDuringUndoMustPreventOperationFinalization() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path source = tempDir.resolve("v32-removed-source.fb2");
        Path target = Files.writeString(tempDir.resolve("v32-removed-target.fb2"), "verified-content");
        String digest = sha256(target);
        OrganizationJournalItem item = moveItem(operationId, source, target, digest);
        MemoryJournal journal = new MemoryJournal(operation(operationId), item);
        Path manifest = com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.manifestFor(operationId);
        try {
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, legacyManifest(operationId, item.itemId(), source, target, digest));
            journal.onItemUpdated = () -> {
                try { Files.deleteIfExists(manifest); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            };
            assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                    new LibraryOperationCoordinator(), List.of()).undo(operationId))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest");
            assertThat(Files.readString(source)).isEqualTo("verified-content");
            assertThat(target).doesNotExist();
            assertThat(journal.lastOperationState).isNull();
        } finally {
            com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace.deleteTree(manifest.getParent());
        }
    }

    private String legacyManifest(UUID operationId, UUID itemId, Path source, Path target, String digest) {
        return """
                {"schemaVersion":2,"operationId":"%s","profileId":"safe",
                "sourceRoot":"%s","destinationRoot":"%s","state":"FAILED",
                "updatedAt":"2026-10-17T00:00:00Z","items":[
                {"itemId":"%s","action":"MOVE","sourcePath":"%s","sourceEntry":"",
                "targetPath":"%s","state":"COMMITTED","sourceSha256":"%s",
                "targetSha256":"%s"}]}
                """.formatted(operationId, jsonPath(tempDir), jsonPath(tempDir),
                        itemId, jsonPath(source), jsonPath(target), digest, digest);
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private OrganizationJournalOperation operationForCollection(UUID operationId, String collectionId) {
        return new OrganizationJournalOperation(operationId, "safe", tempDir, tempDir,
                OrganizationOperationState.FAILED, Instant.now(), null, "{}", collectionId);
    }

    private OrganizationJournalItem moveItem(UUID operationId, Path source, Path target, String hash) {
        return new OrganizationJournalItem(operationId, UUID.randomUUID(), source, "", target, hash, hash,
                "MOVE", OrganizationItemState.COMMITTED, null, "", 0, 0, 0, 0.0, "{}", "", "");
    }

    private CollectionLifecyclePort changingLifecycle(AtomicReference<String> activeId) {
        return new CollectionLifecyclePort() {
            @Override public void switchToCollection(Collection collection) { activeId.set(collection.getId()); }
            @Override public void closeCurrentCollection() { activeId.set(""); }
            @Override public Collection getCurrentCollection() {
                String id = activeId.get();
                return id.isEmpty() ? null : new Collection(id, id, tempDir, "collection.db", 0, null, null, null, null);
            }
            @Override public void updateCurrentCollection(Collection collection) { }
            @Override public boolean hasActiveCollection() { return !activeId.get().isEmpty(); }
            @Override public boolean isCollectionReady() { return !activeId.get().isEmpty(); }
            @Override public long getDatabaseSize() { return 0; }
        };
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

    @Test
    void concurrentItemInsertedAtFinalizationNeverMarksOperationRolledBack() throws Exception {
        UUID operationId = UUID.randomUUID();
        MemoryJournal journal = new MemoryJournal(operation(operationId));
        journal.onBeforeFinalization = () -> journal.items.add(
                moveItem(operationId, tempDir.resolve("new-source.fb2"),
                         tempDir.resolve("new-target.fb2"), "new-hash"));
        assertThatThrownBy(() -> new UndoLibraryOrganizationUseCase(journal,
                new LibraryOperationCoordinator(), List.of()).undo(operationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("atomic finalization rejected");
        assertThat(journal.lastOperationState).isNull();
    }

    private static final class MemoryJournal implements OrganizationJournalPort {
        private final OrganizationJournalOperation operation;
        private final List<OrganizationJournalItem> items;
        private Runnable onBeforeFinalization;
        private Runnable onItemsRead;
        private Runnable onItemUpdated;
        private Runnable onBeforeConditionalUpdate;
        private OrganizationJournalItem lastUpdatedItem;
        private OrganizationOperationState lastOperationState;

        private MemoryJournal(OrganizationJournalOperation operation, OrganizationJournalItem... items) {
            this.operation = operation;
            this.items = new ArrayList<>(List.of(items));
        }

        @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items) { }
        @Override public void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson) {
            lastOperationState = state;
        }
        @Override public void updateItem(OrganizationJournalItem item) {
            lastUpdatedItem = item;
            items.replaceAll(existing -> existing.itemId().equals(item.itemId()) ? item : existing);
            if (onItemUpdated != null) onItemUpdated.run();
        }
        @Override public boolean updateItemIfUnchanged(OrganizationJournalItem expected,
                                                         OrganizationJournalItem replacement) {
            if (onBeforeConditionalUpdate != null) onBeforeConditionalUpdate.run();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).operationId().equals(expected.operationId())
                        && items.get(i).itemId().equals(expected.itemId())) {
                    if (!items.get(i).equals(expected)) return false;
                    updateItem(replacement);
                    return true;
                }
            }
            return false;
        }
        @Override public boolean finalizeRollbackIfUnchanged(OrganizationJournalOperation expected,
                                                               List<UUID> itemIds, String summary) {
            if (onBeforeFinalization != null) onBeforeFinalization.run();
            if (!operation.equals(expected)) return false;
            Set<UUID> unique = new HashSet<>(itemIds);
            if (unique.size() != itemIds.size() || unique.size() != items.size()) return false;
            for (OrganizationJournalItem item : items) {
                if (!unique.remove(item.itemId()) || !item.operationId().equals(expected.operationId())
                        || (item.state() != OrganizationItemState.ROLLED_BACK
                            && item.state() != OrganizationItemState.SKIPPED)) return false;
            }
            if (!unique.isEmpty()) return false;
            lastOperationState = OrganizationOperationState.ROLLED_BACK;
            return true;
        }
        @Override public java.util.Optional<OrganizationJournalOperation> findOperation(UUID id) { return operation.operationId().equals(id) ? java.util.Optional.of(operation) : java.util.Optional.empty(); }
        @Override public List<OrganizationJournalOperation> incompleteOperations() { return List.of(operation); }
        @Override public List<OrganizationJournalItem> items(UUID operationId) {
            if (onItemsRead != null) onItemsRead.run();
            return List.copyOf(items);
        }
    }
}
