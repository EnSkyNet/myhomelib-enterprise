package com.myhomelibcorp.infrastructure.executor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringExecutorAdapterTest {

    @Test
    void rejectedSubmissionIsReturnedAsFailedFutureInsteadOfRunningOnCaller() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("adapter-test-");
        executor.initialize();
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                try {
                    blocker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            SpringExecutorAdapter adapter = new SpringExecutorAdapter(executor);
            var rejected = adapter.submit(() -> "never");
            assertThat(rejected).isCompletedExceptionally();
            assertThatThrownBy(rejected::join).hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally {
            blocker.countDown();
            executor.shutdown();
        }
    }
}
