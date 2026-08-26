package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Crash-loss-bounded reader autosave. Position changes are copied out of the FX
 * thread and flushed to SQLite every three seconds. Workspace close always calls
 * flush() synchronously, while unexpected process termination loses at most the
 * most recent interval rather than relying on a JavaFX AnimationTimer pulse.
 */
@Slf4j
final class ReaderPositionAutosaver implements AutoCloseable {
    private final NewReaderPersistenceService persistence;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reader-position-autosave");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ReaderPosition> latest = new AtomicReference<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile String bookId;

    ReaderPositionAutosaver(NewReaderPersistenceService persistence) {
        this.persistence = persistence;
        executor.scheduleWithFixedDelay(this::flushIfDirty, 3, 3, TimeUnit.SECONDS);
    }

    void start(String bookId) {
        flush();
        this.bookId = bookId;
        latest.set(null);
        dirty.set(false);
    }

    void mark(ReaderPosition position) {
        if (position == null || bookId == null) return;
        latest.set(position);
        dirty.set(true);
    }

    void flush() {
        flushIfDirty();
    }

    private void flushIfDirty() {
        String id = bookId;
        ReaderPosition pos = latest.get();
        if (id == null || pos == null || !dirty.compareAndSet(true, false)) return;
        try {
            persistence.savePosition(id, pos);
        } catch (Exception e) {
            dirty.set(true);
            log.warn("Reader autosave failed for {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void close() {
        flush();
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        bookId = null;
    }
}
