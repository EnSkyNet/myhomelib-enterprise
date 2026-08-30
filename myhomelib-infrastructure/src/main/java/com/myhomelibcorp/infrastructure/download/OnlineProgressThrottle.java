package com.myhomelibcorp.infrastructure.download;

/**
 * Thread-confined progress callback throttle for high-throughput HTTP transfers.
 * Cancellation must still be checked on every read; this class only limits callback frequency.
 */
public final class OnlineProgressThrottle {
    private static final long MIN_BYTES = 1024L * 1024L;
    private static final long MIN_NANOS = 100_000_000L;

    private long lastBytes;
    private long lastNanos;

    public OnlineProgressThrottle(long initialBytes) {
        this.lastBytes = Math.max(0L, initialBytes);
        this.lastNanos = System.nanoTime();
    }

    public boolean shouldEmit(long processedBytes, long totalBytes) {
        long processed = Math.max(0L, processedBytes);
        long now = System.nanoTime();
        boolean complete = totalBytes > 0L && processed >= totalBytes;
        if (!complete && processed - lastBytes < MIN_BYTES && now - lastNanos < MIN_NANOS) {
            return false;
        }
        lastBytes = processed;
        lastNanos = now;
        return true;
    }
}
