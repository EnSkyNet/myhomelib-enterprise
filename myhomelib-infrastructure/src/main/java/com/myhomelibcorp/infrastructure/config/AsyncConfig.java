package com.myhomelibcorp.infrastructure.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Єдині bounded managed executors для backend-задач застосунку.
 *
 * <p>Жоден pool не використовує CallerRunsPolicy: при saturation задача явно
 * відхиляється, щоб background I/O/CPU робота ніколи не виконувалась на FX або
 * іншому caller thread.</p>
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private ThreadPoolTaskExecutor taskExecutor;
    private ThreadPoolTaskExecutor ioExecutor;
    private ThreadPoolTaskExecutor importExecutor;
    private ThreadPoolTaskExecutor searchExecutor;

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        taskExecutor = create("task", 5, 20, 200, "app-task-", 30);
        return taskExecutor;
    }

    @Bean(name = "ioExecutor")
    public ThreadPoolTaskExecutor ioExecutor() {
        ioExecutor = create("io", 4, 16, 200, "app-io-", 60);
        return ioExecutor;
    }

    @Bean(name = "importExecutor")
    public ThreadPoolTaskExecutor importExecutor() {
        importExecutor = create("import", 2, 10, 100, "app-import-", 60);
        return importExecutor;
    }

    @Bean(name = "searchExecutor")
    public ThreadPoolTaskExecutor searchExecutor() {
        searchExecutor = create("search", 2, 8, 50, "app-search-", 30);
        return searchExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        shutdown("task", taskExecutor);
        shutdown("io", ioExecutor);
        shutdown("import", importExecutor);
        shutdown("search", searchExecutor);
    }

    private void shutdown(String role, ThreadPoolTaskExecutor executor) {
        if (executor == null) return;
        log.info("Stopping managed executor role={}, queueDepth={}, active={}",
                role, executor.getQueueSize(), executor.getActiveCount());
        executor.shutdown();
    }

    private ThreadPoolTaskExecutor create(
            String role,
            int core,
            int max,
            int queueCapacity,
            String threadPrefix,
            int shutdownSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownSeconds);
        executor.setRejectedExecutionHandler((task, pool) -> reject(role, pool));
        executor.initialize();
        log.info("Executor role={} initialized: core={}, max={}, queueCapacity={}, prefix={}",
                role, core, max, queueCapacity, threadPrefix);
        return executor;
    }

    public Map<String, ExecutorMetrics> metrics() {
        Map<String, ExecutorMetrics> result = new LinkedHashMap<>();
        addMetrics(result, "task", taskExecutor);
        addMetrics(result, "io", ioExecutor);
        addMetrics(result, "import", importExecutor);
        addMetrics(result, "search", searchExecutor);
        return Map.copyOf(result);
    }

    private void addMetrics(Map<String, ExecutorMetrics> result, String role, ThreadPoolTaskExecutor executor) {
        if (executor == null) return;
        result.put(role, new ExecutorMetrics(
                executor.getActiveCount(), executor.getPoolSize(), executor.getQueueSize(),
                executor.getThreadPoolExecutor().getQueue().remainingCapacity()));
    }

    public record ExecutorMetrics(int active, int poolSize, int queueDepth, int queueRemainingCapacity) { }

    private void reject(String role, ThreadPoolExecutor pool) {
        int queueDepth = pool == null ? -1 : pool.getQueue().size();
        int active = pool == null ? -1 : pool.getActiveCount();
        int poolSize = pool == null ? -1 : pool.getPoolSize();
        log.warn("Executor saturated: role={}, queueDepth={}, active={}, poolSize={}",
                role, queueDepth, active, poolSize);
        throw new RejectedExecutionException(
                "Background executor '" + role + "' is saturated (queueDepth=" + queueDepth + ")");
    }
}
