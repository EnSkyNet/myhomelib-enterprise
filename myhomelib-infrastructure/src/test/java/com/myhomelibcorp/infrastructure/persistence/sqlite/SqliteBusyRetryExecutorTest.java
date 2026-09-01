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
}
