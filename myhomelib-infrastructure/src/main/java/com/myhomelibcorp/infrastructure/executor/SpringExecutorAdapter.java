package com.myhomelibcorp.infrastructure.executor;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Application ExecutorPort backed by the shared managed taskExecutor bean. */
@Component
public class SpringExecutorAdapter implements ExecutorPort {

    private final ThreadPoolTaskExecutor taskExecutor;

    public SpringExecutorAdapter(@Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, taskExecutor);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    @Override
    public void execute(Runnable task) {
        taskExecutor.execute(task);
    }

    int activeCount() { return taskExecutor.getActiveCount(); }
    int queueDepth() { return taskExecutor.getQueueSize(); }
}
