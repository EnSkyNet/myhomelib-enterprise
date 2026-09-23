package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UiBackgroundExecutorCollectionScopeTest {

    @Test
    void collectionLeaseIsHeldFromSubmissionUntilWorkerCompletion() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var future = executor.submitCollectionScoped(LibraryOperationType.EDIT, () -> {
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return "done";
            });

            assertEquals(LibraryOperationType.EDIT, coordinator.activeOperation());
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.SWITCH));

            release.countDown();
            assertEquals("done", future.get(2, TimeUnit.SECONDS));
            assertFalse(coordinator.isBusy());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void schedulingRejectionReleasesPreAcquiredLease() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        executor.shutdown();

        var future = executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> "never");

        assertTrue(future.isCompletedExceptionally());
        assertFalse(coordinator.isBusy());
    }
    @Test
    void syncScopeIsAcquiredBeforeQueuedWorkerStarts() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var future = executor.submitCollectionScoped(LibraryOperationType.SYNC, () -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return "ok";
            });

            assertThrows(com.myhomelibcorp.application.operation.LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.SWITCH));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("ok", future.get(2, TimeUnit.SECONDS));
            assertFalse(coordinator.isBusy());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void completionReleasesQueryLeaseBeforeDifferentContinuationStarts() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        try {
            var chained = executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> "book")
                    .thenCompose(value -> executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> value + "-downloaded"));

            assertEquals("book-downloaded", chained.get(2, TimeUnit.SECONDS));
            assertFalse(coordinator.isBusy());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void cancellingRunningCollectionScopedTaskKeepsLeaseUntilWorkerActuallyExits() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var future = executor.submitCollectionScopedCancellable(LibraryOperationType.QUERY, () -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Simulate cleanup that must finish before SWITCH is allowed.
                    }
                }
                return "done";
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertTrue(future.cancel(true));
            assertEquals(LibraryOperationType.QUERY, coordinator.activeOperation());
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.SWITCH));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (coordinator.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(coordinator.isBusy());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

}
