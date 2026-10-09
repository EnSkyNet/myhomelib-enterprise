package com.myhomelibcorp.application.textprovider;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextProviderExecutionSupportTest {

    @Test
    void rejectsNonPositiveTimeoutBeforeSubmission() {
        ExecutorPort executor = rejectingExecutor();

        assertThatThrownBy(() -> TextProviderExecutionSupport.submitBounded(
                executor, Duration.ZERO, new AtomicBoolean(), () -> "ok", () -> "unavailable", error -> "failed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
    }

    @Test
    void executorRejectionMapsToUnavailableResult() throws Exception {
        String result = TextProviderExecutionSupport.submitBounded(
                        rejectingExecutor(), Duration.ofSeconds(1), new AtomicBoolean(),
                        () -> "ok", () -> "unavailable", error -> "failed")
                .get(1, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("unavailable");
    }

    @Test
    void hardTimeoutPropagatesCancellationAndInvokesCancelHook() throws Exception {
        CountDownLatch cancelHook = new CountDownLatch(1);
        AtomicBoolean cancellation = new AtomicBoolean();
        CompletableFuture<String> pending = new CompletableFuture<>();
        ExecutorPort executor = new ExecutorPort() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                throw new UnsupportedOperationException("submitCancellable is used directly");
            }
            @Override public <T> CancellableTask<T> submitCancellable(Callable<T> task) {
                @SuppressWarnings("unchecked")
                CompletableFuture<T> typed = (CompletableFuture<T>) pending;
                return new CancellableTask<>(typed, cancelHook::countDown);
            }
            @Override public void execute(Runnable task) {
                throw new UnsupportedOperationException("not used");
            }
        };

        String result = TextProviderExecutionSupport.submitBounded(
                        executor, Duration.ofMillis(30), cancellation,
                        () -> "late",
                        () -> "unavailable",
                        error -> TextProviderFailures.unwrap(error) instanceof java.util.concurrent.TimeoutException
                                ? "timeout" : "failed")
                .get(1, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("timeout");
        assertThat(cancellation).isTrue();
        assertThat(cancelHook.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private static ExecutorPort rejectingExecutor() {
        return new ExecutorPort() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                throw new java.util.concurrent.RejectedExecutionException("closed");
            }
            @Override public void execute(Runnable task) {
                throw new java.util.concurrent.RejectedExecutionException("closed");
            }
        };
    }
}
