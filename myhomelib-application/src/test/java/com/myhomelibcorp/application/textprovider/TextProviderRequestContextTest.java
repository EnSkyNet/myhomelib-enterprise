package com.myhomelibcorp.application.textprovider;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextProviderRequestContextTest {
    @Test
    void reportsCancellationBeforeDeadline() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        TextProviderRequestContext context = TextProviderRequestContext.create(Duration.ofSeconds(1), cancelled);

        assertThat(context.isCancelled()).isFalse();
        cancelled.set(true);

        assertThatThrownBy(context::throwIfStopped)
                .isInstanceOf(TextProviderException.class)
                .satisfies(error -> assertThat(((TextProviderException) error).kind())
                        .isEqualTo(TextProviderErrorKind.CANCELLED));
    }

    @Test
    void deterministicClockExpiresDeadline() {
        AtomicLong clock = new AtomicLong(100L);
        TextProviderRequestContext context = new TextProviderRequestContext(
                Duration.ofNanos(50), new AtomicBoolean(false), clock::get);

        clock.set(149L);
        assertThat(context.remainingTimeout()).isEqualTo(Duration.ofNanos(1));
        clock.set(150L);

        assertThat(context.isTimedOut()).isTrue();
        assertThatThrownBy(context::throwIfStopped)
                .isInstanceOf(TextProviderException.class)
                .satisfies(error -> assertThat(((TextProviderException) error).kind())
                        .isEqualTo(TextProviderErrorKind.TIMEOUT));
    }
}
