package com.myhomelibcorp.application.textprovider;

import com.myhomelibcorp.application.util.RequestDeadline;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Cooperative cancellation/deadline contract shared by dictionary and translation providers. */
public final class TextProviderRequestContext {
    private final RequestDeadline deadline;

    private TextProviderRequestContext(Duration timeout, AtomicBoolean cancelFlag) {
        this.deadline = RequestDeadline.start(timeout, cancelFlag == null ? null : cancelFlag::get);
    }

    TextProviderRequestContext(Duration timeout, AtomicBoolean cancelFlag, LongSupplier clock) {
        this.deadline = RequestDeadline.start(timeout, cancelFlag == null ? null : cancelFlag::get, clock);
    }

    public static TextProviderRequestContext create(Duration timeout, AtomicBoolean cancelFlag) {
        return new TextProviderRequestContext(timeout, cancelFlag);
    }

    public boolean isCancelled() {
        return deadline.isCancelled();
    }

    public boolean isTimedOut() {
        return deadline.isTimedOut();
    }

    public Duration remainingTimeout() {
        return deadline.remainingTimeout();
    }

    public void throwIfStopped() throws TextProviderException {
        if (isCancelled()) throw TextProviderException.cancelled();
        if (isTimedOut()) throw TextProviderException.timeout();
    }
}
