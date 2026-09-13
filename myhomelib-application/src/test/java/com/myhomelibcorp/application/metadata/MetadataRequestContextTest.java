package com.myhomelibcorp.application.metadata;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataRequestContextTest {
    @Test
    void negativeNanoTimeOriginDoesNotExpireFreshRequest() {
        AtomicLong clock = new AtomicLong(-1_000);
        var context = new MetadataRequestContext(Duration.ofNanos(100), new AtomicBoolean(), clock::get);
        assertThat(context.remainingTimeout()).isEqualTo(Duration.ofNanos(100));
        clock.addAndGet(99);
        assertThat(context.remainingTimeout()).isEqualTo(Duration.ofNanos(1));
        clock.incrementAndGet();
        assertThat(context.isTimedOut()).isTrue();
    }

    @Test
    void signedNanoTimeWraparoundPreservesElapsedDuration() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10);
        var context = new MetadataRequestContext(Duration.ofNanos(30), new AtomicBoolean(), clock::get);
        clock.addAndGet(20);
        assertThat(context.remainingTimeout()).isEqualTo(Duration.ofNanos(10));
        clock.addAndGet(10);
        assertThat(context.isTimedOut()).isTrue();
    }

    @Test
    void hugeDurationSaturatesAndStillSupportsCancellation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var context = new MetadataRequestContext(Duration.ofSeconds(Long.MAX_VALUE), cancelled, () -> -50L);
        assertThat(context.remainingTimeout()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
        cancelled.set(true);
        assertThat(context.isCancelled()).isTrue();
    }
}
