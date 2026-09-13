package com.myhomelibcorp.application.textprovider;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Cooperative cancellation/deadline contract shared by dictionary and translation providers. */
public final class TextProviderRequestContext {
    private final AtomicBoolean cancelFlag;
    private final long timeoutNanos;
    private final long startedNanos;
    private final LongSupplier clock;

    private TextProviderRequestContext(Duration timeout, AtomicBoolean cancelFlag) {
        this(timeout, cancelFlag, System::nanoTime);
    }

    TextProviderRequestContext(Duration timeout, AtomicBoolean cancelFlag, LongSupplier clock) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.cancelFlag = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
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

    public static TextProviderRequestContext create(Duration timeout, AtomicBoolean cancelFlag) {
        return new TextProviderRequestContext(timeout, cancelFlag);
    }

    public boolean isCancelled() {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }

    public boolean isTimedOut() {
        return remainingTimeout().isZero();
    }

    public Duration remainingTimeout() {
        long elapsed = clock.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed >= timeoutNanos) return Duration.ZERO;
        return Duration.ofNanos(timeoutNanos - elapsed);
    }

    public void throwIfStopped() throws TextProviderException {
        if (isCancelled()) throw TextProviderException.cancelled();
        if (isTimedOut()) throw TextProviderException.timeout();
    }
}
