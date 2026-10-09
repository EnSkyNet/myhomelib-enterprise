package com.myhomelibcorp.application.textprovider;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared bounded-execution mechanics for text providers.
 *
 * <p>Provider selection and result semantics intentionally remain in the dictionary/translation
 * services. This class owns only executor submission, hard timeout enforcement and cancellation
 * propagation so those lifecycle rules cannot drift apart.</p>
 */
public final class TextProviderExecutionSupport {
    private TextProviderExecutionSupport() { }

    public static void requirePositiveTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static <T> CompletableFuture<T> submitBounded(
            ExecutorPort executor,
            Duration timeout,
            AtomicBoolean cancellation,
            Callable<T> task,
            Supplier<T> unavailableResult,
            Function<Throwable, T> failureMapper) {
        Objects.requireNonNull(executor, "executor");
        requirePositiveTimeout(timeout);
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailableResult, "unavailableResult");
        Objects.requireNonNull(failureMapper, "failureMapper");

        ExecutorPort.CancellableTask<T> submitted;
        try {
            submitted = executor.submitCancellable(task);
        } catch (RuntimeException rejected) {
            return CompletableFuture.completedFuture(unavailableResult.get());
        }
        if (submitted == null || submitted.future() == null) {
            return CompletableFuture.completedFuture(unavailableResult.get());
        }

        return submitted.future().orTimeout(timeoutMillis(timeout), TimeUnit.MILLISECONDS)
                .handle((result, failure) -> {
                    if (failure == null) return result;
                    if (TextProviderFailures.unwrap(failure) instanceof TimeoutException) {
                        cancellation.set(true);
                        submitted.cancel();
                    }
                    return failureMapper.apply(failure);
                });
    }

    private static long timeoutMillis(Duration timeout) {
        try {
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
