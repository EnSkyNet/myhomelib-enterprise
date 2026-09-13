package com.myhomelibcorp.application.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Host-created execution context. Plugin/provider code cannot construct one outside this package. */
public final class AiProviderContext {
    private final AtomicBoolean cancelFlag;
    private final long deadlineNanos;
    private final Set<String> allowedSecrets;
    private final Function<String, Optional<String>> secretReader;

    AiProviderContext(
            Duration timeout,
            AtomicBoolean cancelFlag,
            Set<String> allowedSecrets,
            Function<String, Optional<String>> secretReader
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.cancelFlag = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        long now = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }
        this.deadlineNanos = timeoutNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
        this.allowedSecrets = Set.copyOf(Objects.requireNonNull(allowedSecrets, "allowedSecrets"));
        this.secretReader = Objects.requireNonNull(secretReader, "secretReader");
    }

    public boolean isCancelled() {
        return cancelFlag.get() || Thread.currentThread().isInterrupted();
    }

    public Duration remainingTimeout() {
        if (isCancelled()) return Duration.ZERO;
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    public void throwIfStopped() throws AiProviderException {
        if (isCancelled()) throw AiProviderException.cancelled();
        if (remainingTimeout().isZero()) throw AiProviderException.timeout();
    }

    /** Read only a provider-declared secret. Arbitrary host secrets are not addressable. */
    public Optional<String> secret(String name) {
        if (name == null || !allowedSecrets.contains(name)) {
            throw new IllegalArgumentException("AI provider requested undeclared secret");
        }
        return secretReader.apply(name);
    }
}
