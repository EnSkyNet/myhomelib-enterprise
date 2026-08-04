package com.myhomelibcorp.infrastructure.executor;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SpringExecutorAdapter implements ExecutorPort {

    private final ThreadPoolTaskExecutor taskExecutor;

    public SpringExecutorAdapter() {
        this.taskExecutor = new ThreadPoolTaskExecutor();
        this.taskExecutor.setCorePoolSize(4);
        this.taskExecutor.setMaxPoolSize(20);
        this.taskExecutor.setQueueCapacity(200);
        this.taskExecutor.setThreadNamePrefix("AppExecutor-");
        this.taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        this.taskExecutor.setAwaitTerminationSeconds(30);
        this.taskExecutor.initialize();
        log.info("SpringExecutorAdapter ініціалізовано з пулом потоків");
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, taskExecutor);
    }

    @Override
    public void execute(Runnable task) {
        taskExecutor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Завершення SpringExecutorAdapter...");
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)) {
                taskExecutor.getThreadPoolExecutor().shutdownNow();
                if (!taskExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("SpringExecutorAdapter не завершив роботу примусово");
                }
            }
        } catch (InterruptedException e) {
            taskExecutor.getThreadPoolExecutor().shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SpringExecutorAdapter завершено");
    }
}