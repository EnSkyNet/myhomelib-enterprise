package com.myhomelibcorp.ui.service;

import jakarta.annotation.PreDestroy;
import javafx.concurrent.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
@Slf4j
public class BackgroundTaskService {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;

    private final ThreadPoolExecutor executor;

    public BackgroundTaskService() {
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
                        t.setName("bg-task-" + t.getId());
                        t.setDaemon(true);
                        return t;
                    }
                },
                // ВИПРАВЛЕНО: замість CallerRunsPolicy використовуємо AbortPolicy з логуванням
                new ThreadPoolExecutor.AbortPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        log.warn("Background task rejected! Queue full (size={}), active={}",
                                e.getQueue().size(), e.getActiveCount());
                        throw new RejectedExecutionException("Background task queue is full");
                    }
                }
        );
        log.info("BackgroundTaskService ініціалізовано: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    /**
     * Виконує задачу у фоновому потоці.
     */
    public <T> CompletableFuture<T> runAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Виконує задачу у фоновому потоці без повернення результату.
     */
    public void runAsync(Runnable task) {
        executor.execute(task);
    }

    /**
     * Створює JavaFX Task для відображення прогресу.
     */
    public <T> Task<T> createTask(Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        return new Task<T>() {
            @Override
            protected T call() throws Exception {
                return callable.call();
            }

            @Override
            protected void succeeded() {
                if (onSuccess != null) {
                    onSuccess.accept(getValue());
                }
            }

            @Override
            protected void failed() {
                if (onError != null) {
                    onError.accept(getException());
                }
            }
        };
    }

    /**
     * Повертає поточний розмір пулу (для моніторингу).
     */
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    /**
     * Повертає розмір черги (для моніторингу).
     */
    public int getQueueSize() {
        return executor.getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Завершення BackgroundTaskService... Активних задач: {}, Черга: {}",
                executor.getActiveCount(), executor.getQueue().size());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("BackgroundTaskService не завершив роботу примусово");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("BackgroundTaskService завершено");
    }
}