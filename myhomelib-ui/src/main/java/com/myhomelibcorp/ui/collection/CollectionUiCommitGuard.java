package com.myhomelibcorp.ui.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.service.CollectionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Commits JavaFX collection state only while the backend still has the collection that produced
 * the asynchronous result active. The short QUERY lease closes the post-SWITCH gap between a
 * completed worker future and its later JavaFX callback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class CollectionUiCommitGuard {
    private final CollectionManagementService collectionManagementService;
    private final LibraryOperationCoordinator operationCoordinator;

    public boolean runIfStillActive(String expectedCollectionId, Runnable uiCommit) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank() || uiCommit == null) return false;
        final LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        } catch (RuntimeException lifecycleBusy) {
            log.debug("Skipping stale collection UI commit for {} because lifecycle is busy", expectedCollectionId);
            return false;
        }
        try {
            var current = collectionManagementService.getCurrentCollection();
            String actual = current == null ? null : current.getId();
            if (!Objects.equals(expectedCollectionId, actual)) {
                log.debug("Skipping stale collection UI commit: expected={}, actual={}", expectedCollectionId, actual);
                lease.markCompleted("");
                return false;
            }
            uiCommit.run();
            lease.markCompleted("");
            return true;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }
}
