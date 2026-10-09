package com.myhomelibcorp.reader.render.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedTaskGroupTest {

    @Test
    void groupCancellationInterruptsRunningTaskAndRemovesQueuedTasks() throws Exception {
        ThreadPoolExecutor executor = TrackedTaskGroup.newSingleThreadExecutor("tracked-task-test");
        TrackedTaskGroup group = new TrackedTaskGroup(executor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            Future<?> running = group.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> queued = group.submit(() -> { });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            group.cancelOutstanding();

            assertThat(running.isCancelled()).isTrue();
            assertThat(queued.isCancelled()).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getQueue()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedTaskIsNotRetainedAndExecutorCanAcceptNextTask() throws Exception {
        ThreadPoolExecutor executor = TrackedTaskGroup.newSingleThreadExecutor("tracked-task-reuse-test");
        TrackedTaskGroup group = new TrackedTaskGroup(executor);
        try {
            group.submit(() -> { }).get(1, TimeUnit.SECONDS);
            group.cancelOutstanding();
            assertThat(group.submit(() -> { }).get(1, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }
}
