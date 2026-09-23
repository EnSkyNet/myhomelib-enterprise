package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.audio.AudioPosition;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
final class AudioPositionAutosaver implements AutoCloseable {
    private final NewReaderPersistenceService persistence;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audiobook-position-autosave");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    AudioPositionAutosaver(NewReaderPersistenceService persistence) {
        this.persistence = persistence;
        executor.scheduleWithFixedDelay(this::flushIfDirty, 3, 3, TimeUnit.SECONDS);
    }

    void start(String collectionId, String bookId) {
        flush();
        state.updateAndGet(previous -> new State(
                collectionId, bookId, null, 0.0, false, previous.generation() + 1));
    }

    void mark(AudioPosition position, double percent) {
        if (position == null) return;
        double normalized = Math.max(0.0, Math.min(100.0, percent));
        state.updateAndGet(current -> current.bookId() == null
                ? current
                : new State(current.collectionId(), current.bookId(), position, normalized, true, current.generation()));
    }

    boolean flush() { return flushIfDirty(); }

    private boolean flushIfDirty() {
        State snapshot;
        while (true) {
            snapshot = state.get();
            if (snapshot.bookId() == null || snapshot.latest() == null || !snapshot.dirty()) return true;
            State clean = new State(snapshot.collectionId(), snapshot.bookId(), snapshot.latest(),
                    snapshot.percent(), false, snapshot.generation());
            if (state.compareAndSet(snapshot, clean)) break;
        }
        try {
            if (persistence.saveAudioPosition(snapshot.collectionId(), snapshot.bookId(), snapshot.latest(), snapshot.percent())) return true;
            restoreDirty(snapshot);
            log.warn("Audiobook autosave failed for {}; position remains dirty", snapshot.bookId());
            return false;
        } catch (Exception e) {
            restoreDirty(snapshot);
            log.warn("Audiobook autosave failed for {}: {}", snapshot.bookId(), e.getMessage());
            return false;
        }
    }

    private void restoreDirty(State failed) {
        state.updateAndGet(current -> current.generation() != failed.generation()
                ? current
                : new State(current.collectionId(), current.bookId(), current.latest(), current.percent(),
                        true, current.generation()));
    }

    @Override
    public void close() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        state.updateAndGet(previous -> new State(null, null, null, 0.0, false, previous.generation() + 1));
    }

    private record State(String collectionId, String bookId, AudioPosition latest, double percent,
                         boolean dirty, long generation) {
        private static State empty() { return new State(null, null, null, 0.0, false, 0L); }
    }
}
