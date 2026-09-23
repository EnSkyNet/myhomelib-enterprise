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
