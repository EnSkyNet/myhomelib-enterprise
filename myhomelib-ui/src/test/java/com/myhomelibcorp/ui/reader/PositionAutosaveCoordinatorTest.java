package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PositionAutosaveCoordinatorTest {

    @Test
    void schedulerThreadIsLazyUntilFirstBookStarts() throws Exception {
        String threadName = "autosave-lazy-test-" + UUID.randomUUID();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", threadName, (collectionId, bookId, position, auxiliary) -> true);
        try {
            assertThat(threadExists(threadName)).isFalse();

            coordinator.start("collection", "book", 100L);

            Instant deadline = Instant.now().plus(Duration.ofSeconds(1));
            while (!threadExists(threadName) && Instant.now().isBefore(deadline)) {
                Thread.sleep(10L);
            }
            assertThat(threadExists(threadName)).isTrue();
        } finally {
            coordinator.close();
        }
    }


    @Test
    void dirtyPositionIsFlushedByThreeSecondSchedulerWithoutManualFlush() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        AtomicReference<String> savedPosition = new AtomicReference<>();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-periodic-test-" + UUID.randomUUID(),
                (collectionId, bookId, position, auxiliary) -> {
                    savedPosition.set(position);
                    saved.countDown();
                    return true;
                });
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("chapter-7");

            assertThat(saved.await(4500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(savedPosition.get()).isEqualTo("chapter-7");
        } finally {
            coordinator.close();
        }
    }

    @Test
    void lateOlderFlushCannotOverwriteNewerCommittedReadingPosition() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-revision-test", (collectionId, bookId, position, auxiliary) -> {
                    stored.set(position);
                    return true;
                });
        try {
            // Model an older flush that registered BEFORE its snapshot was persisted,
            // then got descheduled while a newer snapshot committed. Its reference
            // must keep the per-book revision fence alive until the older flush exits.
            Class<?> snapshot = Arrays.stream(PositionAutosaveCoordinator.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("Snapshot")).findFirst().orElseThrow();
            Constructor<?> snapshotCtor = snapshot.getDeclaredConstructor(
                    String.class, String.class, Object.class, Object.class, boolean.class, long.class);
            snapshotCtor.setAccessible(true);
            Object newer = snapshotCtor.newInstance("collection", "book", "newer", 0L, true, 2L);
            Object older = snapshotCtor.newInstance("collection", "book", "older", 0L, true, 1L);

            Class<?> keyType = Arrays.stream(PositionAutosaveCoordinator.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("BookKey")).findFirst().orElseThrow();
            Constructor<?> keyCtor = keyType.getDeclaredConstructor(String.class, String.class);
            keyCtor.setAccessible(true);
            Object key = keyCtor.newInstance("collection", "book");
            Class<?> saveStateType = Arrays.stream(PositionAutosaveCoordinator.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("BookSaveState")).findFirst().orElseThrow();
            Method register = PositionAutosaveCoordinator.class.getDeclaredMethod("register", keyType);
            Method release = PositionAutosaveCoordinator.class.getDeclaredMethod("release", keyType, saveStateType);
            Method persist = PositionAutosaveCoordinator.class.getDeclaredMethod(
                    "persistRegistered", keyType, snapshot, saveStateType);
            register.setAccessible(true);
            release.setAccessible(true);
            persist.setAccessible(true);
            Object oldRegistration = register.invoke(coordinator, key);
            try {
                Object newRegistration = register.invoke(coordinator, key);
                try {
                    assertThat((Boolean) persist.invoke(coordinator, key, newer, newRegistration)).isTrue();
                } finally {
                    release.invoke(coordinator, key, newRegistration);
                }
                assertThat(coordinator.trackedBookCount()).isEqualTo(1);
                assertThat((Boolean) persist.invoke(coordinator, key, older, oldRegistration)).isTrue();
            } finally {
                release.invoke(coordinator, key, oldRegistration);
            }
            assertThat(stored.get()).isEqualTo("newer");
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void successfulPositionsDoNotAccumulatePerBookLocksOrRevisionEntries() {
        AtomicInteger saved = new AtomicInteger();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-many-books-test", (collectionId, bookId, position, auxiliary) -> {
                    saved.incrementAndGet();
                    return true;
                });
        try {
            for (int i = 0; i < 20_000; i++) {
                coordinator.start("collection", "book-" + i, 100L);
                coordinator.mark("position-" + i);
                assertThat(coordinator.flush()).isTrue();
            }
            assertThat(saved.get()).isEqualTo(20_000);
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void onlyUnsavedSnapshotsRetainRevisionFenceUntilRecovery() {
        AtomicBoolean recovered = new AtomicBoolean(false);
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-failure-retention-test", (collectionId, bookId, position, auxiliary) ->
                        !bookId.equals("bad") || recovered.get());
        try {
            coordinator.start("collection", "bad", 100L);
            coordinator.mark("must-not-lose");
            assertThat(coordinator.flush()).isFalse();
            coordinator.start("collection", "good", 100L);
            coordinator.mark("saved");
            assertThat(coordinator.flush()).isTrue();
            assertThat(coordinator.trackedBookCount()).isEqualTo(1);

            recovered.set(true);
            assertThat(coordinator.flush()).isTrue();
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void closingCannotClaimSuccessWhileAnotherFlushIsStillWriting() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-close-race-test", (collectionId, bookId, position, auxiliary) -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return false;
                });
        var pool = Executors.newSingleThreadExecutor();
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("unsaved");
            Future<Boolean> flushing = pool.submit(coordinator::flush);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            // Prior code returned true and dropped the retry state even though the
            // independent flush had not finished (and would eventually fail).
            assertThat(coordinator.closeAndFlush()).isFalse();
            assertThat(coordinator.flush()).isFalse();
            assertThat(coordinator.trackedBookCount()).isGreaterThan(0);
            release.countDown();
            assertThat(flushing.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(coordinator.closeAndFlush()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void closingWaitsForActiveSuccessfulFlushAndReleasesItsBookState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-close-drain-test", (collectionId, bookId, position, auxiliary) -> {
                    entered.countDown();
                    return release.await(5, TimeUnit.SECONDS);
                });
        var pool = Executors.newFixedThreadPool(2);
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("position");
            Future<Boolean> flushing = pool.submit(coordinator::flush);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> closing = pool.submit(coordinator::closeAndFlush);
            Thread.sleep(75L);
            release.countDown();
            assertThat(flushing.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void failedCloseRetainsLastPositionAndCanBeRetriedWhenStorageRecovers() {
        AtomicBoolean databaseAvailable = new AtomicBoolean(false);
        AtomicReference<String> stored = new AtomicReference<>();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-close-retry-test", (collectionId, bookId, position, auxiliary) -> {
                    if (!databaseAvailable.get()) return false;
                    stored.set(position);
                    return true;
                });
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("final-reading-position");
            assertThat(coordinator.closeAndFlush()).isFalse();
            assertThat(coordinator.trackedBookCount()).isEqualTo(1);

            databaseAvailable.set(true);
            assertThat(coordinator.closeAndFlush()).isTrue();
            assertThat(stored.get()).isEqualTo("final-reading-position");
            assertThat(coordinator.trackedBookCount()).isZero();
            assertThat(coordinator.closeAndFlush()).isTrue();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void newerSuccessfulSnapshotSupersedesFailedOldRetryDuringClose() {
        AtomicReference<String> stored = new AtomicReference<>();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-close-superseding-test", (collectionId, bookId, position, auxiliary) -> {
                    if (position.equals("older")) return false;
                    stored.set(position);
                    return true;
                });
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("older");
            assertThat(coordinator.flush()).isFalse();
            coordinator.mark("newer");
            assertThat(coordinator.closeAndFlush()).isTrue();
            assertThat(stored.get()).isEqualTo("newer");
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            coordinator.close();
        }
    }

    @Test
    void closeCanRetryAfterTimedOutWriteFinishesWithFailure() throws Exception {
        CountDownLatch insideSave = new CountDownLatch(1);
        CountDownLatch completeSave = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> stored = new AtomicReference<>();
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-close-timeout-retry-test", (collectionId, bookId, position, auxiliary) -> {
                    if (attempts.getAndIncrement() == 0) {
                        insideSave.countDown();
                        completeSave.await(10, TimeUnit.SECONDS);
                        return false;
                    }
                    stored.set(position);
                    return true;
                });
        var pool = Executors.newSingleThreadExecutor();
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("last-position");
            Future<Boolean> firstFlush = pool.submit(coordinator::flush);
            assertThat(insideSave.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(coordinator.closeAndFlush()).isFalse();
            assertThat(coordinator.trackedBookCount()).isEqualTo(1);
            completeSave.countDown();
            assertThat(firstFlush.get(5, TimeUnit.SECONDS)).isFalse();

            assertThat(coordinator.closeAndFlush()).isTrue();
            assertThat(stored.get()).isEqualTo("last-position");
            assertThat(coordinator.trackedBookCount()).isZero();
        } finally {
            completeSave.countDown();
            pool.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void simultaneousCloseDoesNotReportSuccessBeforeFirstCloseFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PositionAutosaveCoordinator<String, Long> coordinator = new PositionAutosaveCoordinator<>(
                "Test", "autosave-simultaneous-close-test", (collectionId, bookId, position, auxiliary) -> {
                    entered.countDown();
                    return release.await(5, TimeUnit.SECONDS);
                });
        var pool = Executors.newSingleThreadExecutor();
        try {
            coordinator.start("collection", "book", 100L);
            coordinator.mark("reading-position");
            Future<Boolean> firstClose = pool.submit(coordinator::closeAndFlush);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.closeAndFlush()).isFalse();
            release.countDown();
            assertThat(firstClose.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.closeAndFlush()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
            coordinator.close();
        }
    }

    private static boolean threadExists(String threadName) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> threadName.equals(thread.getName()) && thread.isAlive());
    }
}
