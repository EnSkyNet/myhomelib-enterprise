package com.myhomelibcorp.application.port.out.executor;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public interface ExecutorPort {
    <T> CompletableFuture<T> submit(Callable<T> task);
    void execute(Runnable task);

    /**
     * Submits work together with an explicit cancellation hook. The default implementation keeps
     * compatibility with lightweight/test executors; managed executors should override it when
     * they can interrupt the actual worker rather than only the CompletableFuture facade.
     */
    default <T> CancellableTask<T> submitCancellable(Callable<T> task) {
        CompletableFuture<T> future = submit(task);
        return new CancellableTask<>(future, () -> future.cancel(true));
    }

    record CancellableTask<T>(CompletableFuture<T> future, Runnable cancelAction) {
        public CancellableTask {
            Objects.requireNonNull(future, "future");
            Objects.requireNonNull(cancelAction, "cancelAction");
        }

        public void cancel() {
            cancelAction.run();
        }
    }
}
