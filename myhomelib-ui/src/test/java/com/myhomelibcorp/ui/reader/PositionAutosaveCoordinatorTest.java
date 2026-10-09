package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

    private static boolean threadExists(String threadName) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> threadName.equals(thread.getName()) && thread.isAlive());
    }
}
