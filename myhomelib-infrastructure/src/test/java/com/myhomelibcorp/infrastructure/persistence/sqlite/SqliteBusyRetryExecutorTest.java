package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteBusyRetryExecutorTest {

    @Test
    void retriesTransientBusyAndEventuallySucceeds() {
        SqliteBusyRetryExecutor executor = new SqliteBusyRetryExecutor();
        AtomicInteger attempts = new AtomicInteger();

        String value = executor.execute("test write", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("[SQLITE_BUSY] The database file is locked (database is locked)");
            }
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryNonBusyFailures() {
        SqliteBusyRetryExecutor executor = new SqliteBusyRetryExecutor();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("test write", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("invalid data");
        })).isInstanceOf(IllegalStateException.class).hasMessage("invalid data");

        assertThat(attempts).hasValue(1);
    }
    @Test
    void cyclicNonBusyCauseChainReturnsFalseWithoutHanging() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                () -> assertThat(SqliteBusyRetryExecutor.isBusy(first)).isFalse());
    }

    @Test
    void cyclicCauseChainStillDetectsSqliteBusyMessage() {
        IllegalStateException first = new IllegalStateException("wrapper");
        IllegalStateException second = new IllegalStateException("database is locked");
        first.initCause(second);
        second.initCause(first);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                () -> assertThat(SqliteBusyRetryExecutor.isBusy(first)).isTrue());
    }

    @Test
    void busyCauseNestedInsideThreeNodeCycleCanBeRecognized() {
        IllegalStateException a = new IllegalStateException("outer");
        IllegalStateException b = new IllegalStateException("other");
        IllegalStateException c = new IllegalStateException("SQLITE_BUSY retryable");
        a.initCause(b); b.initCause(c); c.initCause(a);
        assertThat(SqliteBusyRetryExecutor.isBusy(a)).isTrue();
    }
}
