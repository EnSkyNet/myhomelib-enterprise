package com.myhomelibcorp.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigTest {

    @Test
    void executorsHaveNamedThreadsAndExposeQueueMetrics() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor io = config.ioExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            List<String> threadNames = new ArrayList<>();
            io.execute(() -> {
                threadNames.add(Thread.currentThread().getName());
                ran.countDown();
            });
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames).singleElement().asString().startsWith("app-io-");
            assertThat(config.metrics()).containsKey("io");
            assertThat(config.metrics().get("io").queueDepth()).isGreaterThanOrEqualTo(0);
        } finally {
            config.shutdown();
        }
    }

    @Test
    void saturatedExecutorRejectsInsteadOfRunningOnCallerThread() throws Exception {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor search = config.searchExecutor();
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean ranOnCaller = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();
        try {
            // 8 workers + 50 queue slots = 58 admitted blocking tasks.
            for (int i = 0; i < 58; i++) {
                search.execute(() -> {
                    if (Thread.currentThread() == caller) ranOnCaller.set(true);
                    try {
                        blocker.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThatThrownBy(() -> search.execute(() -> ranOnCaller.set(Thread.currentThread() == caller)))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasRootCauseMessage("Background executor 'search' is saturated (queueDepth=50)");
            assertThat(ranOnCaller).isFalse();
            assertThat(config.metrics().get("search").queueDepth()).isEqualTo(50);
        } finally {
            blocker.countDown();
            config.shutdown();
        }
    }
    @Test
    void shutdownStopsAllCreatedManagedExecutors() {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor task = config.taskExecutor();
        ThreadPoolTaskExecutor io = config.ioExecutor();
        ThreadPoolTaskExecutor imports = config.importExecutor();
        ThreadPoolTaskExecutor search = config.searchExecutor();

        config.shutdown();
        config.shutdown();

        assertThat(task.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(io.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(imports.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(search.getThreadPoolExecutor().isShutdown()).isTrue();
    }

}
