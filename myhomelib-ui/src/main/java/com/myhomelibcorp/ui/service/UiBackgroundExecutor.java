package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.shared.util.AsyncCallSupport;
import com.myhomelibcorp.shared.util.ExecutorShutdown;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Component
@Slf4j
public class UiBackgroundExecutor {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 12;
    private static final int QUEUE_CAPACITY = 200;
    private static final long KEEP_ALIVE_TIME = 60L;

    private final ThreadPoolExecutor executor;
    private final LibraryOperationCoordinator operationCoordinator;

    public UiBackgroundExecutor(LibraryOperationCoordinator operationCoordinator) {
        this.operationCoordinator = java.util.Objects.requireNonNull(operationCoordinator, "operationCoordinator");
        this.executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadFactory() {
                    private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = defaultFactory.newThread(r);
                        t.setName("ui-bg-" + t.threadId());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        log.warn("UI background task rejected! Queue full (size={}), active={}",
                                e.getQueue().size(), e.getActiveCount());
                        throw new RejectedExecutionException("UI background task queue is full");
                    }
                }
        );
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return AsyncCallSupport.submit(task, executor);
    }

    /**
     * Acquires the collection lifecycle barrier before the task is submitted to the executor.
     * The detached shared lease remains owned until the worker completes, closing the queue-gap
     * where a SWITCH could otherwise happen after a click but before the worker starts.
     */
    public <T> CompletableFuture<T> submitCollectionScoped(LibraryOperationType operationType, Callable<T> task) {
        if (operationType == null) throw new IllegalArgumentException("operationType is required");
        if (task == null) throw new IllegalArgumentException("task is required");

        final LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetachedShared(operationType);
        } catch (RuntimeException acquisitionFailure) {
            // submit* is an asynchronous API. Returning an exceptional future keeps caller cleanup
            // chains (whenComplete/handle) attached even when the lifecycle barrier is busy. A
            // synchronous throw here can otherwise leak UI operation rows created immediately before
            // submission and leave them permanently in the "running" state.
            return CompletableFuture.failedFuture(acquisitionFailure);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    T value = task.call();
                    lease.markCompleted("");
                    // Release the lifecycle barrier before publishing completion. CompletableFuture
                    // continuations may run inline in complete(), and some of them legitimately
                    // start a different collection-scoped operation (for example QUERY -> DOWNLOAD).
                    lease.close();
                    result.complete(value);
                } catch (Throwable failure) {
                    lease.markFailed(failure);
                    lease.close();
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            lease.markFailed(schedulingFailure);
            lease.close();
            result.completeExceptionally(schedulingFailure);
        }
        return result;
    }

    public CompletableFuture<Void> submitCollectionScoped(LibraryOperationType operationType, Runnable task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        return submitCollectionScoped(operationType, () -> {
            task.run();
            return null;
        });
    }


    /**
     * Cancellable variant of {@link #submitCollectionScoped(LibraryOperationType, Callable)}.
     * The lifecycle lease is acquired before enqueue. Cancellation before execution releases it
     * immediately; cancellation of a running task keeps it held until the worker actually exits.
     */
    public <T> Future<T> submitCollectionScopedCancellable(LibraryOperationType operationType, Callable<T> task) {
        if (operationType == null) throw new IllegalArgumentException("operationType is required");
        if (task == null) throw new IllegalArgumentException("task is required");

        LibraryOperationCoordinator.Lease lease = operationCoordinator.acquireDetachedShared(operationType);
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
        FutureTask<T> future = new FutureTask<>(() -> {
            started.set(true);
            try {
                T value = task.call();
                lease.markCompleted("");
                return value;
            } catch (Throwable failure) {
                lease.markFailed(failure);
                throw failure;
            } finally {
                lease.close();
            }
        }) {
            @Override
            protected void done() {
                if (isCancelled() && !started.get()) {
                    lease.markFailed(new CancellationException("Collection-scoped UI task cancelled before execution"));
                    lease.close();
                }
            }
        };
        try {
            executor.execute(future);
            return future;
        } catch (RuntimeException schedulingFailure) {
            lease.markFailed(schedulingFailure);
            lease.close();
            throw schedulingFailure;
        }
    }

    /** Submit a task whose Future cancellation interrupts the worker thread. */
    public <T> Future<T> submitCancellable(Callable<T> task) {
        return executor.submit(task);
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        ExecutorShutdown.gracefully(executor, 5, TimeUnit.SECONDS);
    }
}