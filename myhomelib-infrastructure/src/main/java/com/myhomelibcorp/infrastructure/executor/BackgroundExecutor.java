package com.myhomelibcorp.infrastructure.executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Compatibility facade for legacy infrastructure callers.
 * Uses the shared managed I/O executor instead of owning another private pool.
 */
@Component
public class BackgroundExecutor implements java.util.concurrent.Executor {

    private final ThreadPoolTaskExecutor executor;

    public BackgroundExecutor(@Qualifier("ioExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    public int getActiveCount() { return executor.getActiveCount(); }
    public int getQueueDepth() { return executor.getQueueSize(); }
}
