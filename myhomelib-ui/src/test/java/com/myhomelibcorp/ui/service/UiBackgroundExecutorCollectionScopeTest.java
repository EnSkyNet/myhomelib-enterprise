package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationOutcome;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UiBackgroundExecutorCollectionScopeTest {

    @Test
    void idleCoreThreadsAreAllowedToRetire() {
        var pool = UiBackgroundExecutor.createExecutor();
        try {
            assertTrue(pool.allowsCoreThreadTimeOut());
            assertEquals(30L, pool.getKeepAliveTime(TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void plainPresentationSubmitDoesNotAcquireLibraryLease() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var future = executor.submit(() -> {
                entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return "details";
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(coordinator.isBusy());
            try (var switchLease = coordinator.acquire(LibraryOperationType.SWITCH)) {
                assertEquals(LibraryOperationType.SWITCH, coordinator.activeOperation());
            }

            release.countDown();
            assertEquals("details", future.get(2, TimeUnit.SECONDS));
            assertFalse(coordinator.isBusy());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

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
    void deferredStatisticsRefreshWaitsForIndexWithoutBlockingCaller() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        try {
            var index = coordinator.acquireDetachedShared(LibraryOperationType.INDEX);
            try {
                var stats = executor.submitCollectionScopedAwait(LibraryOperationType.EDIT, () -> "refreshed");
                assertEquals(LibraryOperationType.INDEX, coordinator.activeOperation());
                assertFalse(stats.isDone(), "Refresh must be deferred, not rejected or run inside INDEX");
                index.markCompleted("");
                index.close();
                assertEquals("refreshed", stats.get(3, TimeUnit.SECONDS));
                assertFalse(coordinator.isBusy());
            } finally {
                index.close();
            }
        } finally {
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

    @Test
    void cancellationFromNestedSharedOperationRemainsCancelledAtRoot() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        coordinator.addCompletionListener(completion -> outcome.set(completion.outcome()));
        try {
            var future = executor.submitCollectionScoped(LibraryOperationType.CONVERSION, () -> {
                try (var inner = coordinator.acquireDetachedShared(LibraryOperationType.CONVERSION)) {
                    inner.markCancelled("cancelled");
                }
                throw new CancellationException("cancelled");
            });

            assertThrows(CancellationException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertEquals(LibraryOperationOutcome.CANCELLED, outcome.get());
            assertFalse(coordinator.isBusy());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void cooperativeCancellationFromCancellableTaskRemainsCancelledAtRoot() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        AtomicReference<LibraryOperationOutcome> outcome = new AtomicReference<>();
        coordinator.addCompletionListener(completion -> outcome.set(completion.outcome()));
        try {
            var future = executor.submitCollectionScopedCancellable(LibraryOperationType.QUERY, () -> {
                throw new CancellationException("cancelled cooperatively");
            });

            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(LibraryOperationOutcome.CANCELLED, outcome.get());
            assertFalse(coordinator.isBusy());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void backgroundIndexDoesNotRejectUserDownloadAtUiSubmissionBoundary() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        var indexLease = coordinator.acquireDetachedShared(LibraryOperationType.INDEX);
        try {
            var future = executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> "downloaded");

            assertEquals("downloaded", future.get(2, TimeUnit.SECONDS));
            assertEquals(LibraryOperationType.INDEX, coordinator.activeOperation());
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.SWITCH));
        } finally {
            indexLease.close();
            executor.shutdown();
        }
        assertFalse(coordinator.isBusy());
    }

    @Test
    void blockedNetworkTasksDoNotStarveInteractiveDetailsLane() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch networkEntered = new CountDownLatch(4);
        CountDownLatch releaseNetwork = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.submitCollectionScopedNetwork(LibraryOperationType.DOWNLOAD, () -> {
                    networkEntered.countDown();
                    assertTrue(releaseNetwork.await(2, TimeUnit.SECONDS));
                    return "download";
                });
            }
            assertTrue(networkEntered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            var details = executor.submitInteractiveCancellable(() -> "details");
            assertEquals("details", details.completion().get(1, TimeUnit.SECONDS));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 500, "interactive work was starved for " + elapsedMs + " ms");
        } finally {
            releaseNetwork.countDown();
            executor.shutdown();
        }
    }

    @Test
    void cancellingInteractiveTaskInterruptsSupersededDetailsWork() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        try {
            var submission = executor.submitInteractiveCancellable(() -> {
                entered.countDown();
                try {
                    Thread.sleep(30_000);
                    return "stale";
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                    throw expected;
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(submission.cancel());
            assertThrows(Exception.class, () -> submission.completion().get(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Boolean.TRUE.equals(interrupted.get()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(interrupted.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shutdownInterruptsInteractiveWorkAndReleasesCollectionLease() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            executor.submitCollectionScopedInteractiveCancellable(LibraryOperationType.QUERY, () -> {
                entered.countDown();
                try {
                    Thread.sleep(30_000);
                    return "unexpected";
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw new CancellationException("shutdown interrupted interactive work");
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(LibraryOperationType.QUERY, coordinator.activeOperation());

            executor.shutdown();

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (coordinator.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(coordinator.isBusy(), "shutdown must not leave a lifecycle lease behind");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void cancellingCollectionScopedInteractiveTaskKeepsLeaseUntilWorkerExits() throws Exception {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        UiBackgroundExecutor executor = new UiBackgroundExecutor(coordinator);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var submission = executor.submitCollectionScopedInteractiveCancellable(LibraryOperationType.QUERY, () -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* cleanup before leaving collection */ }
                }
                return "stale";
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(submission.cancel());
            assertEquals(LibraryOperationType.QUERY, coordinator.activeOperation());
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquire(LibraryOperationType.SWITCH));

            release.countDown();
            assertThrows(Exception.class, () -> submission.completion().get(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (coordinator.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(coordinator.isBusy());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

}
