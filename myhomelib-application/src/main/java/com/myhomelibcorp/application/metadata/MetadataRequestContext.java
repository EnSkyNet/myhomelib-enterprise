package com.myhomelibcorp.application.metadata;

import com.myhomelibcorp.application.util.RequestDeadline;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Cooperative cancellation/deadline contract passed unchanged to every provider implementation. */
public final class MetadataRequestContext {
    private final RequestDeadline deadline;

    private MetadataRequestContext(Duration timeout, AtomicBoolean cancelFlag) {
        this.deadline = RequestDeadline.start(timeout, cancelFlag == null ? null : cancelFlag::get);
    }

    MetadataRequestContext(Duration timeout, AtomicBoolean cancelFlag, LongSupplier clock) {
        this.deadline = RequestDeadline.start(timeout, cancelFlag == null ? null : cancelFlag::get, clock);
    }

    public static MetadataRequestContext create(Duration timeout, AtomicBoolean cancelFlag) {
        return new MetadataRequestContext(timeout, cancelFlag);
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

    /** Providers should call this before/after remote I/O and long parsing steps. */
    public void throwIfStopped() throws MetadataProviderException {
        if (isCancelled()) throw MetadataProviderException.cancelled();
        if (isTimedOut()) throw MetadataProviderException.timeout();
    }
}
