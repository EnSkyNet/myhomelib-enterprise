package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AsyncBatchLaneRunnerTest {
    @Test
    void aHundredThousandImmediateCompletionsNeverBuildADeepFutureChain() throws Exception {
        int total = 100_000;
        AtomicInteger handled = new AtomicInteger();
        CompletableFuture<Void> completed = AsyncBatchLaneRunner.run(total, 3,
                i -> CompletableFuture.completedFuture(i),
                (i, result) -> {
                    assertNull(result.failure());
                    assertEquals(i, result.value());
                    handled.incrementAndGet();
                });
        completed.get(10, TimeUnit.SECONDS);
        assertEquals(total, handled.get());
    }

    @Test
    void onlyOneUnsettledFutureExistsPerLane() throws Exception {
        List<CompletableFuture<Integer>> waiting = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger settled = new AtomicInteger();
        CompletableFuture<Void> batch = AsyncBatchLaneRunner.run(30, 3,
                i -> {
                    CompletableFuture<Integer> step = new CompletableFuture<>();
                    waiting.add(step);
                    started.incrementAndGet();
                    return step;
                }, (i, result) -> {
                    assertNull(result.failure());
                    settled.incrementAndGet();
                });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (started.get() < 3 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(3, started.get());
        assertFalse(batch.isDone());
        for (int index = 0; index < 30; index++) {
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (waiting.size() <= index && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(waiting.size() > index);
            assertTrue(started.get() - settled.get() <= 3, "more than 3 in-flight tasks");
            waiting.get(index).complete(index);
        }
        batch.get(3, TimeUnit.SECONDS);
        assertEquals(30, started.get());
        assertEquals(30, settled.get());
    }

    @Test
    void oneFailedItemDoesNotAbortLaterItems() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        CompletableFuture<Void> batch = AsyncBatchLaneRunner.run(5, 2,
                i -> {
                    if (i == 1) throw new IllegalStateException("broken");
                    if (i == 3) return CompletableFuture.failedFuture(new IllegalArgumentException("bad"));
                    return CompletableFuture.completedFuture(i);
                }, (i, result) -> {
                    if (result.failure() == null) successes.incrementAndGet();
                    else failures.incrementAndGet();
                });
        batch.get(3, TimeUnit.SECONDS);
        assertEquals(2, failures.get());
        assertEquals(3, successes.get());
    }

    @Test
    void failureInResultsHandlerTerminatesTheBatch() {
        CompletableFuture<Void> batch = AsyncBatchLaneRunner.run(10, 1,
                i -> CompletableFuture.completedFuture(i),
                (i, result) -> { throw new IllegalStateException("cannot report result"); });
        var thrown = assertThrows(java.util.concurrent.CompletionException.class, batch::join);
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void emptyBatchNeedsNoWork() {
        assertTrue(AsyncBatchLaneRunner.run(0, 2,
                i -> { throw new AssertionError("should not run"); },
                (i, result) -> { throw new AssertionError("should not settle"); }).isDone());
    }
}
