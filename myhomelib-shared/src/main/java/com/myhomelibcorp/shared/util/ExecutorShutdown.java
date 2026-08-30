package com.myhomelibcorp.shared.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Consistent bounded shutdown for application-owned executor services. */
public final class ExecutorShutdown {
    private ExecutorShutdown() { }

    public static void gracefully(ExecutorService executor, long timeout, TimeUnit unit) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
