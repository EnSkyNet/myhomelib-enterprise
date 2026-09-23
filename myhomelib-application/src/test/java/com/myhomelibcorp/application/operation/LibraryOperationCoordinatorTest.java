package com.myhomelibcorp.application.operation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LibraryOperationCoordinatorTest {

    @Test
    void allowsOnlyExplicitImportNesting() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        try (var create = coordinator.acquire(LibraryOperationType.CREATE)) {
            assertDoesNotThrow(() -> {
                try (var ignored = coordinator.acquire(LibraryOperationType.IMPORT)) { }
            });
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.DELETE));
        }
        assertFalse(coordinator.isBusy());
    }

    @Test
    void updateCannotNestDeleteEvenOnSameThread() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        try (var ignored = coordinator.acquire(LibraryOperationType.UPDATE)) {
            var conflict = assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.DELETE));
            assertEquals(LibraryOperationType.UPDATE, conflict.activeOperation());
        }
    }

    @Test
    void detachedLeaseBlocksInitiatingThreadUntilFutureCompletes() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var lease = coordinator.acquireDetached(LibraryOperationType.SYNC);
        assertThrows(LibraryOperationConflictException.class,
                () -> coordinator.acquire(LibraryOperationType.VACUUM));

        CompletableFuture.runAsync(lease::close).get(2, TimeUnit.SECONDS);
        assertDoesNotThrow(() -> {
            try (var ignored = coordinator.acquire(LibraryOperationType.VACUUM)) { }
        });
    }

    @Test
    void detachedAwaitStartsOnlyAfterCurrentLifecycleLeaseIsReleased() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var switchLease = coordinator.acquire(LibraryOperationType.SWITCH);

        CompletableFuture<LibraryOperationCoordinator.Lease> waiting =
                CompletableFuture.supplyAsync(() -> coordinator.acquireDetachedAwait(LibraryOperationType.INDEX));
        Thread.sleep(30);
        assertFalse(waiting.isDone());
        assertEquals(LibraryOperationType.SWITCH, coordinator.activeOperation());

        switchLease.close();
        var indexLease = waiting.get(2, TimeUnit.SECONDS);
        assertEquals(LibraryOperationType.INDEX, coordinator.activeOperation());
        indexLease.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void anotherThreadCannotEnterActiveOperation() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        try (var ignored = coordinator.acquire(LibraryOperationType.IMPORT)) {
            CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> {
                try (var nested = coordinator.acquire(LibraryOperationType.IMPORT)) {
                    return false;
                } catch (LibraryOperationConflictException expected) {
                    return true;
                }
            });
            assertTrue(blocked.get(2, TimeUnit.SECONDS));
        }
    }
    @Test
    void listenersObserveRootOperationLifecycleWithoutNestedNoise() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        java.util.List<LibraryOperationType> observed = new java.util.ArrayList<>();
        coordinator.addListener(observed::add);

        try (var create = coordinator.acquire(LibraryOperationType.CREATE)) {
            try (var nestedImport = coordinator.acquire(LibraryOperationType.IMPORT)) {
                assertEquals(LibraryOperationType.CREATE, coordinator.activeOperation());
            }
        }

        assertEquals(java.util.Arrays.asList(null, LibraryOperationType.CREATE, null), observed);
    }

    @Test
    void completionListenersReceiveFailureAndDetail() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        java.util.List<LibraryOperationCompletion> completions = new java.util.ArrayList<>();
        coordinator.addCompletionListener(completions::add);

        var lease = coordinator.acquireDetached(LibraryOperationType.INDEX);
        lease.markFailed(new IllegalStateException("index exploded"));
        lease.close();

        assertEquals(1, completions.size());
        assertEquals(LibraryOperationType.INDEX, completions.getFirst().operation());
        assertEquals(LibraryOperationOutcome.FAILED, completions.getFirst().outcome());
        assertEquals("index exploded", completions.getFirst().detail());
    }

    @Test
    void sharedDetachedDownloadsRunInParallelButStillBlockSwitch() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var first = coordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD);
        var second = coordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD);

        assertEquals(LibraryOperationType.DOWNLOAD, coordinator.activeOperation());
        assertThrows(LibraryOperationConflictException.class,
                () -> coordinator.acquire(LibraryOperationType.SWITCH));

        first.close();
        assertTrue(coordinator.isBusy(), "second shared lease must keep the lifecycle barrier active");
        second.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void sharedDetachedSyncCanNestUiAndApplicationBarrierButStillBlocksSwitch() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var uiLease = coordinator.acquireDetachedShared(LibraryOperationType.SYNC);
        var applicationLease = coordinator.acquireDetachedShared(LibraryOperationType.SYNC);

        assertEquals(LibraryOperationType.SYNC, coordinator.activeOperation());
        assertThrows(LibraryOperationConflictException.class,
                () -> coordinator.acquire(LibraryOperationType.SWITCH));

        applicationLease.close();
        assertTrue(coordinator.isBusy());
        uiLease.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void exportAndConversionMayShareOneDetachedBarrier() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var export = coordinator.acquireDetachedShared(LibraryOperationType.EXPORT);
        var conversion = coordinator.acquireDetachedShared(LibraryOperationType.CONVERSION);

        assertEquals(LibraryOperationType.EXPORT, coordinator.activeOperation());
        conversion.close();
        assertTrue(coordinator.isBusy());
        export.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void unrelatedSharedOperationCannotJoinActiveBarrier() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        try (var ignored = coordinator.acquireDetachedShared(LibraryOperationType.EDIT)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD));
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.READER_STATE));
        }
    }


    @Test
    void queryAndReaderStateMayShareBarrierButStillBlockSwitch() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var query = coordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        var readerState = coordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);

        assertEquals(LibraryOperationType.QUERY, coordinator.activeOperation());
        assertThrows(LibraryOperationConflictException.class,
                () -> coordinator.acquire(LibraryOperationType.SWITCH));

        readerState.close();
        assertTrue(coordinator.isBusy());
        query.close();
        assertFalse(coordinator.isBusy());
    }


    @Test
    void nestedQueryMayJoinStableEditAndDownloadRoots() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();

        var edit = coordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        var editQuery = coordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());
        editQuery.close();
        edit.close();

        var download = coordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD);
        var downloadQuery = coordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        assertEquals(LibraryOperationType.DOWNLOAD, coordinator.activeOperation());
        downloadQuery.close();
        download.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void mutationCannotJoinQueryRootAndQueryCannotJoinCollectionSwitchingRoots() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        try (var ignored = coordinator.acquireDetachedShared(LibraryOperationType.QUERY)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.EDIT));
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD));
        }

        try (var ignored = coordinator.acquireDetachedShared(LibraryOperationType.COPY)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.QUERY));
        }

        try (var ignored = coordinator.acquireDetachedShared(LibraryOperationType.SYNC)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetachedShared(LibraryOperationType.QUERY));
        }
    }

    @Test
    void sharedAwaitWaitsForIncompatibleRootThenStartsSharedSession() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var switchLease = coordinator.acquire(LibraryOperationType.SWITCH);

        CompletableFuture<LibraryOperationCoordinator.Lease> waiting =
                CompletableFuture.supplyAsync(() -> coordinator.acquireDetachedSharedAwait(LibraryOperationType.EDIT));
        Thread.sleep(30);
        assertFalse(waiting.isDone());
        assertEquals(LibraryOperationType.SWITCH, coordinator.activeOperation());

        switchLease.close();
        var editLease = waiting.get(2, TimeUnit.SECONDS);
        assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());

        var nestedEdit = coordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        var nestedQuery = coordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        nestedQuery.close();
        nestedEdit.close();
        assertTrue(coordinator.isBusy());
        editLease.close();
        assertFalse(coordinator.isBusy());
    }

    @Test
    void sharedAwaitJoinsCompatibleSharedRootWithoutWaiting() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        var first = coordinator.acquireDetachedShared(LibraryOperationType.EDIT);

        CompletableFuture<LibraryOperationCoordinator.Lease> joined =
                CompletableFuture.supplyAsync(() -> coordinator.acquireDetachedSharedAwait(LibraryOperationType.EDIT));
        var second = joined.get(2, TimeUnit.SECONDS);
        assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());

        first.close();
        assertTrue(coordinator.isBusy());
        second.close();
        assertFalse(coordinator.isBusy());
    }

}
