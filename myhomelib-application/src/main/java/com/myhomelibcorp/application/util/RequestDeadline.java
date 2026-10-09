package com.myhomelibcorp.application.util;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Overflow- and wraparound-safe cooperative cancellation/deadline state for provider requests. */
public final class RequestDeadline {
    private final BooleanSupplier cancelled;
    private final long timeoutNanos;
    private final long startedNanos;
    private final LongSupplier clock;

    private RequestDeadline(
            Duration timeout,
            BooleanSupplier cancelled,
            LongSupplier clock) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.clock = Objects.requireNonNull(clock, "clock");
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        this.timeoutNanos = nanos;
        this.startedNanos = clock.getAsLong();
    }

    public static RequestDeadline start(Duration timeout, BooleanSupplier cancelled) {
        return new RequestDeadline(timeout, cancelled, System::nanoTime);
    }

    public static RequestDeadline start(Duration timeout, BooleanSupplier cancelled, LongSupplier clock) {
        return new RequestDeadline(timeout, cancelled, clock);
    }

    public boolean isCancelled() {
        return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    public boolean isTimedOut() {
        return remainingTimeout().isZero();
    }

    public Duration remainingTimeout() {
        // nanoTime values are signed and may wrap. Subtracting two readings preserves the
        // elapsed interval modulo 2^64 as long as the measured interval stays below 2^63 ns.
        long elapsed = clock.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed >= timeoutNanos) return Duration.ZERO;
        return Duration.ofNanos(timeoutNanos - elapsed);
    }
}
