package com.myhomelibcorp.organizer.apply;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Read-only diagnostic watchdog. It never interrupts the worker, closes a file,
 * changes recovery state or cancels SQLite; its sole effect is a warning containing
 * the last observed organizer I/O stage and a bounded Java thread stack.
 */
final class OrganizerIoStallMonitor implements AutoCloseable {
    private record Checkpoint(String stage, UUID itemId, long bytes, long observedNanos) { }

    private static final long DEFAULT_SILENCE_MILLIS = 30_000L;
    private static final long DEFAULT_POLL_MILLIS = 5_000L;
    private final UUID operationId;
    private final Thread worker;
    private final Consumer<String> warnings;
    private final long silenceNanos;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Checkpoint checkpoint;
    private long lastWarningNanos;

    OrganizerIoStallMonitor(UUID operationId, Consumer<String> warnings) {
        this(operationId, warnings, DEFAULT_SILENCE_MILLIS, DEFAULT_POLL_MILLIS);
    }

    // Package-private intervals permit deterministic, short synthetic stall tests.
    OrganizerIoStallMonitor(UUID operationId, Consumer<String> warnings,
                            long silenceMillis, long pollMillis) {
        if (silenceMillis <= 0 || pollMillis <= 0) throw new IllegalArgumentException("Watchdog durations must be positive");
        this.operationId = java.util.Objects.requireNonNull(operationId);
        this.warnings = java.util.Objects.requireNonNull(warnings);
        this.worker = Thread.currentThread();
        this.silenceNanos = TimeUnit.MILLISECONDS.toNanos(silenceMillis);
        this.checkpoint = new Checkpoint("APPLY_START", null, 0, System.nanoTime());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "myhomelib-organizer-io-diagnostic");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safeInspect, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    void mark(UUID itemId, String stage) {
        mark(itemId, stage, 0L);
    }

    void mark(UUID itemId, String stage, long bytes) {
        if (closed.get()) return;
        Checkpoint old = checkpoint;
        if (old != null && java.util.Objects.equals(old.itemId(), itemId) && old.stage().equals(stage) && old.bytes() == bytes) {
            return; // Repeating a stalled/nonadvancing byte count must not mask a hang.
        }
        checkpoint = new Checkpoint(java.util.Objects.requireNonNull(stage), itemId, bytes, System.nanoTime());
    }

    private void safeInspect() {
        try {
            if (closed.get()) return;
            Checkpoint point = checkpoint;
            long now = System.nanoTime();
            long idle = now - point.observedNanos();
            if (idle < silenceNanos || (lastWarningNanos != 0 && now - lastWarningNanos < silenceNanos)) return;
            lastWarningNanos = now;
            StringBuilder frames = new StringBuilder();
            StackTraceElement[] trace = worker.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 14); i++) {
                if (i > 0) frames.append(" <- ");
                frames.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
                        .append(':').append(trace[i].getLineNumber());
            }
            warnings.accept("ORGANIZER_IO_STALL operationId=" + operationId
                    + " itemId=" + (point.itemId() == null ? "none" : point.itemId())
                    + " stage=" + point.stage() + " bytes=" + point.bytes()
                    + " silentMs=" + TimeUnit.NANOSECONDS.toMillis(idle)
                    + " threadState=" + worker.getState() + " workerStack=" + frames);
        } catch (RuntimeException ignored) {
            // Monitoring must never alter the outcome of the user's file operation.
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) scheduler.shutdownNow();
    }
}
