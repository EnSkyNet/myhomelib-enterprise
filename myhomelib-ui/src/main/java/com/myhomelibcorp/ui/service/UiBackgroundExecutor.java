package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.shared.util.AsyncCallSupport;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class UiBackgroundExecutor {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 12;
    private static final int QUEUE_CAPACITY = 200;
    private static final long KEEP_ALIVE_TIME = 30L;

    private final ThreadPoolExecutor executor;
    /** Dedicated virtual-thread lane for blocking remote/network work. Network timeouts must never
     * consume the small presentation pool and starve Book Details/navigation. */
    private final ExecutorService networkExecutor;
    /** Dedicated latency-sensitive lane. Callers are expected to cancel superseded work. */
    private final ExecutorService interactiveExecutor;
    /** Dedicated lane for long user-started operations such as conversion. It must not wait behind
     * passive catalogue queries queued on the bounded presentation executor. Real concurrency is
     * still limited by the owning service (e.g. conversion batch = 2). */
    private final ExecutorService operationExecutor;
    private final LibraryOperationCoordinator operationCoordinator;

    public UiBackgroundExecutor(LibraryOperationCoordinator operationCoordinator) {
        this.operationCoordinator = java.util.Objects.requireNonNull(operationCoordinator, "operationCoordinator");
        this.executor = createExecutor();
        this.networkExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ui-network-", 0).factory());
        this.interactiveExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ui-interactive-", 0).factory());
        this.operationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ui-operation-", 0).factory());
    }


    static ThreadPoolExecutor createExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
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
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
        return submitCollectionScopedOn(executor, operationType, task);
    }

    /**
     * Defers an incompatible collection operation without blocking JavaFX or occupying the bounded
     * UI query pool. A virtual thread waits for the existing lifecycle barrier to be released.
     * Callers must check their captured collection identity in the task: a SWITCH can finish
     * before the deferred lease is acquired.
     */
    public <T> CompletableFuture<T> submitCollectionScopedAwait(
            LibraryOperationType operationType, Callable<T> task) {
        if (operationType == null) throw new IllegalArgumentException("operationType is required");
        if (task == null) throw new IllegalArgumentException("task is required");
        return AsyncCallSupport.submit(() -> {
            try (LibraryOperationCoordinator.Lease lease =
                         operationCoordinator.acquireDetachedSharedAwait(operationType)) {
                try {
                    T result = task.call();
                    lease.markCompleted("");
                    return result;
                } catch (CancellationException cancellation) {
                    lease.markCancelled("Deferred collection work cancelled");
                    throw cancellation;
                } catch (Exception | Error failure) {
                    lease.markFailed(failure);
                    throw failure;
                }
            }
        }, operationExecutor);
    }

    /**
     * Collection-scoped blocking network work. This uses a separate virtual-thread lane so a slow
     * server/HTTP timeout cannot occupy every presentation worker and make local Book Details look
     * frozen. The caller still controls real network parallelism (for downloads, via downloadSlots).
     */
    public <T> CompletableFuture<T> submitCollectionScopedNetwork(
            LibraryOperationType operationType, Callable<T> task) {
        return submitCollectionScopedOn(networkExecutor, operationType, task);
    }

    /** User-started long operation isolated from passive UI query backlog. */
    public <T> CompletableFuture<T> submitCollectionScopedOperation(
            LibraryOperationType operationType, Callable<T> task) {
        return submitCollectionScopedOn(operationExecutor, operationType, task);
    }

    private <T> CompletableFuture<T> submitCollectionScopedOn(
            Executor executorLane, LibraryOperationType operationType, Callable<T> task) {
        if (operationType == null) throw new IllegalArgumentException("operationType is required");
        if (task == null) throw new IllegalArgumentException("task is required");

        final LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetachedShared(operationType);
        } catch (RuntimeException acquisitionFailure) {
            // submit* is an asynchronous API. Returning an exceptional future keeps caller cleanup
            // chains (whenComplete/handle) attached even when the lifecycle barrier is busy.
            return CompletableFuture.failedFuture(acquisitionFailure);
        }

        ScopedCompletableTask<T> scopedTask = new ScopedCompletableTask<>(task, lease);
        try {
            executorLane.execute(scopedTask);
        } catch (RuntimeException schedulingFailure) {
            scopedTask.reject(schedulingFailure);
        }
        return scopedTask.future();
    }

    /**
     * Collection-scoped cancellable query on the latency-sensitive lane. The lease is acquired
     * before scheduling, but cancellation releases it only after running user code has actually
     * exited, preserving SWITCH/DELETE safety while allowing latest-request-wins workspaces.
     */
    public <T> CancellableSubmission<T> submitCollectionScopedInteractiveCancellable(
            LibraryOperationType operationType, Callable<T> task) {
        if (operationType == null) throw new IllegalArgumentException("operationType is required");
        if (task == null) throw new IllegalArgumentException("task is required");
        final LibraryOperationCoordinator.Lease lease;
        try {
            lease = operationCoordinator.acquireDetachedShared(operationType);
        } catch (RuntimeException acquisitionFailure) {
            CompletableFuture<T> failed = CompletableFuture.failedFuture(acquisitionFailure);
            return new CancellableSubmission<>(CompletableFuture.completedFuture(null), failed);
        }
        ScopedCancellableCompletableTask<T> scoped = new ScopedCancellableCompletableTask<>(task, lease);
        try {
            interactiveExecutor.execute(scoped);
        } catch (RuntimeException schedulingFailure) {
            scoped.reject(schedulingFailure);
        }
        return new CancellableSubmission<>(scoped, scoped.completion());
    }

    /** Submit latency-sensitive work on a lane isolated from downloads/imports. */
    public <T> CancellableSubmission<T> submitInteractiveCancellable(Callable<T> task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        CompletableFuture<T> completion = new CompletableFuture<>();
        FutureTask<T> futureTask = new FutureTask<>(task) {
            @Override
            protected void done() {
                try {
                    T value = get();
                    if (!completion.complete(value) && value instanceof AutoCloseable closeable) {
                        try { closeable.close(); }
                        catch (Exception closeFailure) { log.debug("Cannot close superseded interactive result", closeFailure); }
                    }
                } catch (CancellationException cancelled) {
                    completion.completeExceptionally(cancelled);
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    completion.completeExceptionally(cause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    completion.completeExceptionally(interrupted);
                }
            }
        };
        try {
            interactiveExecutor.execute(futureTask);
        } catch (RuntimeException schedulingFailure) {
            completion.completeExceptionally(schedulingFailure);
        }
        return new CancellableSubmission<>(futureTask, completion);
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
        ScopedFutureTask<T> future = new ScopedFutureTask<>(task, lease);
        try {
            executor.execute(future);
            return future;
        } catch (RuntimeException schedulingFailure) {
            future.reject(schedulingFailure);
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

    public static final class CancellableSubmission<T> {
        private final Future<?> task;
        private final CompletableFuture<T> completion;

        private CancellableSubmission(Future<?> task, CompletableFuture<T> completion) {
            this.task = task;
            this.completion = completion;
        }

        public CompletableFuture<T> completion() { return completion; }
        public boolean cancel() { return task.cancel(true); }
    }

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(interactiveExecutor, "interactive");
        shutdownExecutor(networkExecutor, "network");
        shutdownExecutor(operationExecutor, "operation");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                cancelDroppedTasks(executor.shutdownNow());
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("UI background executor did not terminate after forced shutdown; running tasks keep their lifecycle leases until exit");
                }
            }
        } catch (InterruptedException interrupted) {
            cancelDroppedTasks(executor.shutdownNow());
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdownExecutor(ExecutorService lane, String name) {
        lane.shutdownNow();
        try {
            // Interactive parsing/image tasks are interruptible, but native/ZIP cleanup can need
            // slightly more than one second on Windows. Avoid reporting a false stuck executor
            // while still bounding shutdown strictly.
            if (!lane.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("UI {} executor did not terminate after forced shutdown", name);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void cancelDroppedTasks(List<Runnable> dropped) {
        if (dropped == null || dropped.isEmpty()) return;
        CancellationException shutdown = new CancellationException("UI background executor stopped before task execution");
        for (Runnable runnable : dropped) {
            if (runnable instanceof ScopedCompletableTask<?> scoped) {
                scoped.reject(shutdown);
            } else if (runnable instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    /** Runnable whose pre-acquired collection lease is also safe when shutdownNow removes it from the queue. */
    private static final class ScopedCompletableTask<T> implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final Callable<T> callable;
        private final LibraryOperationCoordinator.Lease lease;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicInteger phase = new AtomicInteger(QUEUED);

        private ScopedCompletableTask(Callable<T> callable, LibraryOperationCoordinator.Lease lease) {
            this.callable = callable;
            this.lease = lease;
        }

        private CompletableFuture<T> future() {
            return result;
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            try {
                T value = callable.call();
                lease.markCompleted("");
                // Release before publishing completion: continuations can immediately start another
                // incompatible collection-scoped operation on this same worker thread.
                lease.close();
                result.complete(value);
            } catch (Throwable failure) {
                if (failure instanceof CancellationException) lease.markCancelled(UiExceptionSupport.message(failure));
                else lease.markFailed(failure);
                lease.close();
                result.completeExceptionally(failure);
            } finally {
                phase.set(FINISHED);
            }
        }

        private void reject(Throwable failure) {
            if (!phase.compareAndSet(QUEUED, FINISHED)) return;
            if (failure instanceof CancellationException) lease.markCancelled(UiExceptionSupport.message(failure));
            else lease.markFailed(failure);
            lease.close();
            result.completeExceptionally(failure);
        }
    }

    /** Cancellable CompletableFuture bridge with the same lease lifetime guarantees as ScopedFutureTask. */
    private static final class ScopedCancellableCompletableTask<T> extends FutureTask<T> {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final LibraryOperationCoordinator.Lease lease;
        private final AtomicInteger phase = new AtomicInteger(QUEUED);
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private ScopedCancellableCompletableTask(Callable<T> callable, LibraryOperationCoordinator.Lease lease) {
            super(callable);
            this.lease = lease;
        }

        private CompletableFuture<T> completion() { return completion; }

        @Override
        public void run() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            try {
                super.run();
            } finally {
                try {
                    if (isCancelled()) {
                        lease.markCancelled("Collection-scoped interactive task cancelled");
                        completion.completeExceptionally(new CancellationException("Collection-scoped interactive task cancelled"));
                    } else {
                        try {
                            T value = get();
                            lease.markCompleted("");
                            completion.complete(value);
                        } catch (ExecutionException failure) {
                            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                            if (cause instanceof CancellationException) lease.markCancelled(UiExceptionSupport.message(cause));
                            else lease.markFailed(cause);
                            completion.completeExceptionally(cause);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            lease.markCancelled(UiExceptionSupport.message(interrupted));
                            completion.completeExceptionally(interrupted);
                        }
                    }
                } finally {
                    lease.close();
                    phase.set(FINISHED);
                }
            }
        }

        @Override
        protected void done() {
            // FutureTask.done runs synchronously inside cancel(). A queued task can release now;
            // a running task must wait for run() finally so collection lifecycle remains protected.
            if (isCancelled() && phase.compareAndSet(QUEUED, FINISHED)) {
                lease.markCancelled("Collection-scoped interactive task cancelled before execution");
                lease.close();
                completion.completeExceptionally(new CancellationException("Collection-scoped interactive task cancelled before execution"));
            }
        }

        private void reject(RuntimeException failure) {
            if (!phase.compareAndSet(QUEUED, FINISHED)) return;
            lease.markFailed(failure);
            lease.close();
            completion.completeExceptionally(failure);
        }
    }

    /** FutureTask with an explicit QUEUED/RUNNING boundary, removing cancel-vs-start lease races. */
    private static final class ScopedFutureTask<T> extends FutureTask<T> {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final LibraryOperationCoordinator.Lease lease;
        private final AtomicInteger phase = new AtomicInteger(QUEUED);

        private ScopedFutureTask(Callable<T> callable, LibraryOperationCoordinator.Lease lease) {
            super(callable);
            this.lease = lease;
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            try {
                super.run();
            } finally {
                if (isCancelled()) lease.markCancelled("Collection-scoped UI task cancelled");
                lease.close();
                phase.set(FINISHED);
            }
        }

        @Override
        protected void set(T value) {
            lease.markCompleted("");
            super.set(value);
        }

        @Override
        protected void setException(Throwable failure) {
            if (isCancelled() || failure instanceof CancellationException) {
                lease.markCancelled(UiExceptionSupport.message(failure));
            } else {
                lease.markFailed(failure);
            }
            super.setException(failure);
        }

        @Override
        protected void done() {
            // FutureTask.done() runs synchronously inside cancel(). Only a task that has not crossed
            // the QUEUED -> RUNNING boundary may release its lease here. A running cancelled task
            // releases in run() finally after its user code has actually exited.
            if (isCancelled() && phase.compareAndSet(QUEUED, FINISHED)) {
                lease.markCancelled("Collection-scoped UI task cancelled before execution");
                lease.close();
            }
        }

        private void reject(RuntimeException schedulingFailure) {
            if (!phase.compareAndSet(QUEUED, FINISHED)) return;
            lease.markFailed(schedulingFailure);
            lease.close();
        }
    }
}