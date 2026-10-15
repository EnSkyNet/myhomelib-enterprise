package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
            // Reproduce the critical order at the persistence boundary deterministically:
            // the second snapshot commits before the first flush acquires its book lock.
            Class<?> snapshot = Arrays.stream(PositionAutosaveCoordinator.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("Snapshot")).findFirst().orElseThrow();
            Constructor<?> ctor = snapshot.getDeclaredConstructor(
                    String.class, String.class, Object.class, Object.class, boolean.class, long.class);
            ctor.setAccessible(true);
            Object newer = ctor.newInstance("collection", "book", "newer", 0L, true, 2L);
            Object older = ctor.newInstance("collection", "book", "older", 0L, true, 1L);
            Method persist = PositionAutosaveCoordinator.class.getDeclaredMethod("persist", snapshot);
            persist.setAccessible(true);

            assertThat((Boolean) persist.invoke(coordinator, newer)).isTrue();
            assertThat((Boolean) persist.invoke(coordinator, older)).isTrue();
            assertThat(stored.get()).isEqualTo("newer");
        } finally {
            coordinator.close();
        }
    }

    private static boolean threadExists(String threadName) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> threadName.equals(thread.getName()) && thread.isAlive());
    }
}
