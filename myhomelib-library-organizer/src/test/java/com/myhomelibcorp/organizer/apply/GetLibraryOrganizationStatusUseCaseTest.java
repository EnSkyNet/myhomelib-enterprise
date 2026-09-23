package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GetLibraryOrganizationStatusUseCaseTest {
    @TempDir Path tempDir;

    @Test
    void moveValidatedAfterSourceDeletionIsStillDeterministicallyRollbackable() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectory(tempDir.resolve("source"));
        Path destinationRoot = Files.createDirectory(tempDir.resolve("destination"));
        Path source = sourceRoot.resolve("book.fb2");
        Path target = destinationRoot.resolve("book.fb2");
        Files.writeString(target, "book-data");
        String hash = sha256(target);

        OrganizationJournalOperation operation = new OrganizationJournalOperation(operationId, "safe", sourceRoot,
                destinationRoot, OrganizationOperationState.FAILED, Instant.now(), null, "crash");
        OrganizationJournalItem item = new OrganizationJournalItem(operationId, itemId, source, "", target,
                hash, hash, "MOVE", OrganizationItemState.VALIDATED, null,
                "", 0, 0, 0, 0.0, "{}", "", "");
        OrganizationJournalPort journal = journal(operation, item);

        LibraryOrganizationStatus status = new GetLibraryOrganizationStatusUseCase(journal,
                new LibraryOperationCrashManifestReader()).incomplete().stream()
                .filter(candidate -> candidate.operation().operationId().equals(operationId))
                .findFirst().orElseThrow();

        assertThat(status.disposition()).isEqualTo(RecoveryDisposition.ROLLBACK_AVAILABLE);
        assertThat(status.canRollback()).isTrue();
        assertThat(status.needsManualReview()).isFalse();
        assertThat(status.recoverablePendingItems()).isEqualTo(1);
    }

    @Test
    void moveWithUnexpectedPublishedTargetRequiresManualReview() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectory(tempDir.resolve("source2"));
        Path destinationRoot = Files.createDirectory(tempDir.resolve("destination2"));
        Path source = sourceRoot.resolve("book.fb2");
        Path target = destinationRoot.resolve("book.fb2");
        Files.writeString(target, "tampered");
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("expected".getBytes()));

        OrganizationJournalOperation operation = new OrganizationJournalOperation(operationId, "safe", sourceRoot,
                destinationRoot, OrganizationOperationState.FAILED, Instant.now(), null, "crash");
        OrganizationJournalItem item = new OrganizationJournalItem(operationId, itemId, source, "", target,
                expectedHash, expectedHash, "MOVE", OrganizationItemState.VALIDATED, null,
                "", 0, 0, 0, 0.0, "{}", "", "");

        LibraryOrganizationStatus status = new GetLibraryOrganizationStatusUseCase(journal(operation, item),
                new LibraryOperationCrashManifestReader()).incomplete().stream()
                .filter(candidate -> candidate.operation().operationId().equals(operationId))
                .findFirst().orElseThrow();

        assertThat(status.disposition()).isEqualTo(RecoveryDisposition.MANUAL_REVIEW);
        assertThat(status.needsManualReview()).isTrue();
    }

    private static OrganizationJournalPort journal(OrganizationJournalOperation operation, OrganizationJournalItem item) {
        return new OrganizationJournalPort() {
            @Override public void begin(OrganizationJournalOperation ignored, List<OrganizationJournalItem> items) { }
            @Override public void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson) { }
            @Override public void updateItem(OrganizationJournalItem item) { }
            @Override public List<OrganizationJournalOperation> incompleteOperations() { return List.of(operation); }
            @Override public List<OrganizationJournalItem> items(UUID operationId) { return List.of(item); }
        };
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
