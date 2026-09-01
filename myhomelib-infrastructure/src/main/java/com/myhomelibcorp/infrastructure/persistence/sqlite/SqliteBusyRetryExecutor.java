package com.myhomelibcorp.infrastructure.persistence.sqlite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes short collection-DB writes that may otherwise race for SQLite's single writer lock,
 * and retries transient SQLITE_BUSY failures without surfacing them as user-visible errors.
 *
 * This is intentionally used only for short UI/runtime writes. Long import transactions keep
 * their own transaction/batch strategy and must not be wrapped here.
 */
@Component
@Slf4j
public class SqliteBusyRetryExecutor {
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] RETRY_DELAYS_MS = {100L, 200L, 400L, 800L};

    private final ReentrantLock shortWriteLock = new ReentrantLock(true);

    public void run(String operation, Runnable action) {
        execute(operation, () -> {
            action.run();
            return null;
        });
    }

    public <T> T execute(String operation, Supplier<T> action) {
        if (action == null) throw new IllegalArgumentException("action is required");
        String label = operation == null || operation.isBlank() ? "SQLite write" : operation;

        shortWriteLock.lock();
        try {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    return action.get();
                } catch (RuntimeException error) {
                    if (!isBusy(error)) throw error;
                    last = error;
                    if (attempt >= MAX_ATTEMPTS) break;
                    log.debug("{}: SQLite busy, retry {}/{}", label, attempt, MAX_ATTEMPTS);
                    sleep(RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)]);
                }
            }
            log.debug("{}: SQLite remained busy after {} attempts", label, MAX_ATTEMPTS);
            throw last == null ? new IllegalStateException(label + ": SQLite busy") : last;
        } finally {
            shortWriteLock.unlock();
        }
    }

    public static boolean isBusy(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("sqlite_busy") || normalized.contains("database is locked")
                        || normalized.contains("database table is locked")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SQLite write lock", interrupted);
        }
    }
}
