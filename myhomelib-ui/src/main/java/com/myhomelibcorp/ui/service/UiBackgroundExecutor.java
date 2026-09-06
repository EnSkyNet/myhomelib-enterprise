package com.myhomelibcorp.ui.service;

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

    public UiBackgroundExecutor() {
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
                        t.setName("ui-bg-" + t.getId());
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