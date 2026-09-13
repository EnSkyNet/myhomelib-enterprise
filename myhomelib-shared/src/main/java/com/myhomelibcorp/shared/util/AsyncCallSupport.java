package com.myhomelibcorp.shared.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Small shared adapter for Callable -> CompletableFuture on an explicitly managed executor. */
public final class AsyncCallSupport {
    private AsyncCallSupport() {}

    public static <T> CompletableFuture<T> submit(Callable<T> task, Executor executor) {
        if (task == null) throw new IllegalArgumentException("task is required");
        if (executor == null) throw new IllegalArgumentException("executor is required");
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }
}
