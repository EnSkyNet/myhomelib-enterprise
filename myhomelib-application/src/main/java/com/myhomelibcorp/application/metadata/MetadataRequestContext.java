package com.myhomelibcorp.application.metadata;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Cooperative cancellation/deadline contract passed unchanged to every provider implementation. */
public final class MetadataRequestContext {
    private final AtomicBoolean cancelFlag;
    private final long timeoutNanos;
    private final long startedNanos;
    private final LongSupplier clock;

    private MetadataRequestContext(Duration timeout, AtomicBoolean cancelFlag) {
        this(timeout, cancelFlag, System::nanoTime);
    }

    MetadataRequestContext(Duration timeout, AtomicBoolean cancelFlag, LongSupplier clock) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.cancelFlag = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }
        this.timeoutNanos = timeoutNanos;
        this.startedNanos = clock.getAsLong();
    }

    public static MetadataRequestContext create(Duration timeout, AtomicBoolean cancelFlag) {
        return new MetadataRequestContext(timeout, cancelFlag);
    }

    public boolean isCancelled() {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }

    public boolean isTimedOut() {
        return remainingTimeout().isZero();
    }

    public Duration remainingTimeout() {
        // Subtraction works across nanoTime's signed origin and wraparound; absolute
        // deadline saturation does not. Intervals are bounded to Long.MAX_VALUE nanos.
        long elapsed = clock.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed >= timeoutNanos) return Duration.ZERO;
        return Duration.ofNanos(timeoutNanos - elapsed);
    }

    /** Providers should call this before/after remote I/O and long parsing steps. */
    public void throwIfStopped() throws MetadataProviderException {
        if (isCancelled()) throw MetadataProviderException.cancelled();
        if (isTimedOut()) throw MetadataProviderException.timeout();
    }
}
