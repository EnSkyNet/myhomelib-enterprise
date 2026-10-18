package com.myhomelibcorp.ui.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

/**
 * Runs a large indexed batch with a fixed number of asynchronous operations in flight.
 * Only one item per lane is scheduled at a time: no per-book chain of CompletableFutures
 * is preallocated. The work-in-progress drain avoids recursive callbacks when an item
 * returns an already-completed future (for example, a book already available locally).
 */
final class AsyncBatchLaneRunner {
    private AsyncBatchLaneRunner() { }

    record Outcome<T>(T value, Throwable failure) { }

    static <T> CompletableFuture<Void> run(int size, int concurrency,
                                           IntFunction<CompletableFuture<T>> operation,
                                           BiConsumer<Integer, Outcome<T>> settled) {
        if (size < 0) throw new IllegalArgumentException("size must be nonnegative");
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be positive");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(settled, "settled");
        if (size == 0) return CompletableFuture.completedFuture(null);
        int lanesCount = Math.min(size, concurrency);
        CompletableFuture<?>[] completions = new CompletableFuture<?>[lanesCount];
        @SuppressWarnings("unchecked")
        Lane<T>[] lanes = (Lane<T>[]) new Lane<?>[lanesCount];
        for (int i = 0; i < lanesCount; i++) {
            lanes[i] = new Lane<>(i, lanesCount, size, operation, settled);
            completions[i] = lanes[i].completion;
        }
        // All completion futures exist before any lane starts: synchronous callbacks from
        // already-completed operations cannot make the batch finish prematurely.
        CompletableFuture<Void> all = CompletableFuture.allOf(completions);
        for (Lane<T> lane : lanes) {
            try {
                // A failed, immediately completed operation can otherwise drain tens of thousands
                // of entries synchronously on the JavaFX click handler before returning.
                Thread.ofVirtual().name("ui-batch-lane").start(lane::signal);
            } catch (RuntimeException schedulingFailure) {
                lane.completion.completeExceptionally(schedulingFailure);
            }
        }
        return all;
    }

    private static final class Lane<T> {
        private final int stride;
        private final int size;
        private final IntFunction<CompletableFuture<T>> operation;
        private final BiConsumer<Integer, Outcome<T>> settled;
        private final AtomicInteger pendingSignals = new AtomicInteger();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        // Accessed only by the single signal drainer, not by arbitrary completion callbacks.
        private int nextIndex;

        private Lane(int firstIndex, int stride, int size,
                     IntFunction<CompletableFuture<T>> operation,
                     BiConsumer<Integer, Outcome<T>> settled) {
            this.nextIndex = firstIndex;
            this.stride = stride;
            this.size = size;
            this.operation = operation;
            this.settled = settled;
        }

        private void signal() {
            if (pendingSignals.getAndIncrement() != 0) return;
            int pending = 1;
            do {
                if (!completion.isDone()) {
                    if (nextIndex >= size) {
                        completion.complete(null);
                    } else {
                        int index = nextIndex;
                        nextIndex += stride;
                        dispatch(index);
                    }
                }
                pending = pendingSignals.addAndGet(-pending);
            } while (pending != 0);
        }

        private void dispatch(int index) {
            try {
                CompletableFuture<T> future = Objects.requireNonNull(operation.apply(index), "batch operation returned null");
                future.whenComplete((value, failure) -> finish(index, value, failure));
            } catch (Throwable failure) {
                finish(index, null, failure);
            }
        }

        private void finish(int index, T value, Throwable failure) {
            try {
                settled.accept(index, new Outcome<>(value, failure));
            } catch (Throwable callbackFailure) {
                completion.completeExceptionally(callbackFailure);
            } finally {
                // Safe even for synchronous completion: the drainer loops instead of recursing.
                signal();
            }
        }
    }
}
