package com.myhomelibcorp.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    // Поля для зберігання пулів
    private ThreadPoolTaskExecutor taskExecutor;
    private ThreadPoolTaskExecutor importExecutor;
    private ThreadPoolTaskExecutor searchExecutor;

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("AsyncEvent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        this.taskExecutor = executor;  // зберігаємо
        log.info("Асинхронний пул потоків ініціалізовано (константи)");
        return executor;
    }

    @Bean(name = "importExecutor")
    public Executor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ImportEvent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        this.importExecutor = executor;
        log.info("Імпорт-пул потоків ініціалізовано (константи)");
        return executor;
    }

    @Bean(name = "searchExecutor")
    public Executor searchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("SearchEvent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        this.searchExecutor = executor;
        log.info("Пошуковий пул потоків ініціалізовано (константи)");
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Завершення AsyncConfig пулів...");
        shutdownExecutor("taskExecutor", taskExecutor);
        shutdownExecutor("importExecutor", importExecutor);
        shutdownExecutor("searchExecutor", searchExecutor);
    }

    private void shutdownExecutor(String name, ThreadPoolTaskExecutor executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)) {
                executor.getThreadPoolExecutor().shutdownNow();
                if (!executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Executor {} не завершив роботу примусово", name);
                }
            }
        } catch (InterruptedException e) {
            executor.getThreadPoolExecutor().shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Executor {} завершено", name);
    }
}