package com.myhomelibcorp.ui.service;

import javafx.concurrent.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Service
@Slf4j
public class BackgroundTaskService {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Виконує задачу у фоновому потоці.
     */
    public <T> CompletableFuture<T> runAsync(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
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
    public <T> Task<T> createTask(java.util.concurrent.Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onError) {
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

    public void shutdown() {
        executor.shutdown();
    }
}