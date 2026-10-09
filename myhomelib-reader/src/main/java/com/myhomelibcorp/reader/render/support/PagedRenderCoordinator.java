package com.myhomelibcorp.reader.render.support;

import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared render lifecycle for page-addressed JavaFX readers.
 *
 * <p>The coordinator owns one serialized render worker, configuration generations and the
 * single-page render slot. Reconfiguration invalidates queued work atomically from the caller's
 * perspective so PDF/comic views cannot drift in cancellation semantics.</p>
 */
public final class PagedRenderCoordinator implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final TrackedTaskGroup renderTasks;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong singleRenderGeneration = new AtomicLong();
    private Future<?> singleRenderTask;
    private volatile boolean closed;

    public PagedRenderCoordinator(String threadName) {
        this.executor = TrackedTaskGroup.newSingleThreadExecutor(threadName);
        this.renderTasks = new TrackedTaskGroup(executor);
    }

    /** Creates an independently cancellable task group sharing the same serialized worker. */
    public TrackedTaskGroup newTaskGroup() {
        return new TrackedTaskGroup(executor);
    }

    public long generation() {
        return generation.get();
    }

    public boolean isCurrent(long token) {
        return token == generation.get();
    }

    /** Invalidates all render work tied to the current visual configuration. */
    public void invalidate() {
        generation.incrementAndGet();
        singleRenderGeneration.incrementAndGet();
        renderTasks.cancelOutstanding();
        cancelSingleRender();
    }

    /** Starts a new single-page/spread render after cancelling the previous one. */
    public SingleRenderToken beginSingleRender() {
        cancelSingleRender();
        return new SingleRenderToken(generation.get(), singleRenderGeneration.incrementAndGet());
    }

    public boolean isCurrent(SingleRenderToken token) {
        return token != null
                && token.generation() == generation.get()
                && token.singleGeneration() == singleRenderGeneration.get();
    }

    public Future<?> submit(Runnable action) {
        if (closed) throw new RejectedExecutionException("Paged render coordinator is closed");
        return renderTasks.submit(Objects.requireNonNull(action, "action"));
    }

    public void trackSingle(Future<?> task) {
        singleRenderTask = task;
    }

    public void cancelSingleRender() {
        Future<?> task = singleRenderTask;
        singleRenderTask = null;
        renderTasks.cancel(task);
    }

    /**
     * Queues cleanup after already-running render work. Returns false when the worker is closed,
     * allowing the caller to perform a synchronous fallback close.
     */
    public boolean enqueueAfterPending(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closed) return false;
        try {
            executor.submit(action);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        invalidate();
        executor.shutdown();
    }

    public record SingleRenderToken(long generation, long singleGeneration) { }
}
