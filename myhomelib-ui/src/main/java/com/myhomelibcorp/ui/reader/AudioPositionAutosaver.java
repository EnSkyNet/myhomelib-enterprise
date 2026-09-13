package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.audio.AudioPosition;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
final class AudioPositionAutosaver implements AutoCloseable {
    private final NewReaderPersistenceService persistence;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audiobook-position-autosave"); t.setDaemon(true); return t;
    });
    private final AtomicReference<AudioPosition> latest = new AtomicReference<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile String bookId;
    private volatile double latestPercent;

    AudioPositionAutosaver(NewReaderPersistenceService persistence) {
        this.persistence = persistence;
        executor.scheduleWithFixedDelay(this::flushIfDirty, 3, 3, TimeUnit.SECONDS);
    }
    void start(String bookId) { flush(); this.bookId = bookId; this.latestPercent = 0.0; latest.set(null); dirty.set(false); }
    void mark(AudioPosition position, double percent) { if (position != null && bookId != null) { latest.set(position); latestPercent = Math.max(0.0, Math.min(100.0, percent)); dirty.set(true); } }
    boolean flush() { return flushIfDirty(); }
    private boolean flushIfDirty() {
        String id = bookId; AudioPosition p = latest.get();
        if (id == null || p == null || !dirty.compareAndSet(true, false)) return true;
        if (persistence.saveAudioPosition(id, p, latestPercent)) return true;
        dirty.set(true); log.warn("Audiobook autosave failed for {}; position remains dirty", id); return false;
    }
    @Override public void close() { flush(); executor.shutdown(); try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } bookId = null; }
}
