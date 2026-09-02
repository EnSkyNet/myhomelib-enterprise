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
}
