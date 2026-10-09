package com.myhomelibcorp.reader.render.support;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tracks cancellable tasks submitted to a shared reader worker.
 * Each group can be cancelled independently while preserving FIFO execution on the shared worker.
 */
public final class TrackedTaskGroup {
    private final ThreadPoolExecutor executor;
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();

    public TrackedTaskGroup(ThreadPoolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public Future<?> submit(Runnable action) {
        Objects.requireNonNull(action, "action");
        executor.purge();
        FutureTask<Void> task = new FutureTask<>(action, null) {
            @Override
            protected void done() {
                tasks.remove(this);
            }
        };
        tasks.add(task);
        try {
            executor.execute(task);
            return task;
        } catch (RejectedExecutionException rejected) {
            tasks.remove(task);
            throw rejected;
        }
    }

    public void cancel(Future<?> task) {
        if (task != null && !task.isDone()) task.cancel(true);
        executor.purge();
    }

    public void cancelOutstanding() {
        for (Future<?> task : List.copyOf(tasks)) {
            if (!task.isDone()) task.cancel(true);
        }
        tasks.removeIf(Future::isDone);
        executor.purge();
    }

    public static ThreadPoolExecutor newSingleThreadExecutor(String threadName) {
        String name = Objects.requireNonNull(threadName, "threadName").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("threadName is required");
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
