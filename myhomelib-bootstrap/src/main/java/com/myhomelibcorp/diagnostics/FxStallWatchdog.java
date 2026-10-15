package com.myhomelibcorp.diagnostics;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking JavaFX heartbeat. Logs the FX stack if the event loop stops responding.
 * The diagnostic thread is daemon-only and never waits for the FX thread.
 */
public final class FxStallWatchdog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FxStallWatchdog.class);
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final long LOG_COOLDOWN_NANOS = TimeUnit.MINUTES.toNanos(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "myhomelib-fx-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong lastResponse = new AtomicLong(System.nanoTime());
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastWarning;

    public void start() {
        scheduler.scheduleWithFixedDelay(this::checkSafely, 2, 2, TimeUnit.SECONDS);
    }

    private void checkSafely() {
        if (closed.get()) return;
        try {
            if (pending.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    lastResponse.set(System.nanoTime());
                    pending.set(false);
                });
            }
            long now = System.nanoTime();
            long elapsed = now - lastResponse.get();
            if (elapsed >= STALL_NANOS && (lastWarning == 0 || now - lastWarning >= LOG_COOLDOWN_NANOS)) {
                lastWarning = now;
                log.warn("FX_WATCHDOG_STALL delayMs={} pendingPulse={}\n{}",
                        TimeUnit.NANOSECONDS.toMillis(elapsed), pending.get(), snapshot());
            }
        } catch (Exception failure) {
            log.warn("FX_WATCHDOG_DIAGNOSTIC_FAILED", failure);
        }
    }

    static String snapshot() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder report = new StringBuilder(8192);
        long[] deadlocked = bean.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            report.append("JVM_DEADLOCK_THREADS=").append(java.util.Arrays.toString(deadlocked)).append('\n');
        }
        ThreadInfo[] threads = bean.dumpAllThreads(true, true);
        int count = 0;
        for (ThreadInfo info : threads) {
            if (info == null) continue;
            String name = info.getThreadName();
            if (!(name.contains("JavaFX Application Thread") || name.startsWith("ui-")
                    || name.startsWith("app-") || name.startsWith("myhomelib-"))) continue;
            if (++count > 28) { report.append("...additional threads omitted\n"); break; }
            report.append('"').append(name).append('"').append(" id=").append(info.getThreadId())
                    .append(" state=").append(info.getThreadState())
                    .append(" lock=").append(info.getLockName())
                    .append(" owner=").append(info.getLockOwnerName()).append('\n');
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 45); i++) {
                report.append("  at ").append(stack[i]).append('\n');
            }
        }
        return report.toString();
    }

    @Override public void close() {
        closed.set(true);
        scheduler.shutdownNow();
    }
}
