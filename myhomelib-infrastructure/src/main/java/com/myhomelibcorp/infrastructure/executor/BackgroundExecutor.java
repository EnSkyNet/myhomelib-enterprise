package com.myhomelibcorp.infrastructure.executor;

import com.myhomelibcorp.shared.util.ExecutorShutdown;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class BackgroundExecutor implements java.util.concurrent.Executor {

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
    );

    public <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    @PreDestroy
    public void shutdown() {
        ExecutorShutdown.gracefully(executor, 5, TimeUnit.SECONDS);
    }
}