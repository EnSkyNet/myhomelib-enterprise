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

}
