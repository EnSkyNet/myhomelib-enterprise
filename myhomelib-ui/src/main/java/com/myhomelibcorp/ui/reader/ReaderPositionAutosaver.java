package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Crash-loss-bounded reader autosave. Position changes are copied out of the FX
 * thread and flushed to SQLite every three seconds. Workspace close always calls
 * flush() synchronously, while unexpected process termination loses at most the
 * most recent interval rather than relying on a JavaFX AnimationTimer pulse.
 *
 * <p>The logical state is kept in one atomic snapshot. This is important because
 * {@link #start(String, String, long)} runs on the UI thread while the scheduled flush runs
 * concurrently; publishing {@code bookId}, position and dirty flags separately can
 * otherwise associate the previous book's position with the next book.</p>
 */
@Slf4j
final class ReaderPositionAutosaver implements AutoCloseable {
    private final NewReaderPersistenceService persistence;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reader-position-autosave");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    ReaderPositionAutosaver(NewReaderPersistenceService persistence) {
        this.persistence = persistence;
        executor.scheduleWithFixedDelay(this::flushIfDirty, 3, 3, TimeUnit.SECONDS);
    }

    void start(String collectionId, String bookId, long totalTextLength) {
        flush();
        state.updateAndGet(previous -> new State(
                collectionId, bookId, Math.max(0L, totalTextLength), null, false, previous.generation() + 1));
    }

    void mark(ReaderPosition position) {
        if (position == null) return;
        state.updateAndGet(current -> current.bookId() == null
                ? current
                : new State(current.collectionId(), current.bookId(), current.totalTextLength(), position, true, current.generation()));
    }

    boolean flush() {
        return flushIfDirty();
    }

    private boolean flushIfDirty() {
        State snapshot;
        while (true) {
            snapshot = state.get();
            if (snapshot.bookId() == null || snapshot.latest() == null || !snapshot.dirty()) return true;
            State clean = new State(snapshot.collectionId(), snapshot.bookId(), snapshot.totalTextLength(),
                    snapshot.latest(), false, snapshot.generation());
            if (state.compareAndSet(snapshot, clean)) break;
        }

        try {
            if (persistence.savePosition(snapshot.collectionId(), snapshot.bookId(), snapshot.latest(), snapshot.totalTextLength())) return true;
            restoreDirty(snapshot);
            log.warn("Reader autosave failed for {}; position remains dirty for retry", snapshot.bookId());
            return false;
        } catch (Exception e) {
            restoreDirty(snapshot);
            log.warn("Reader autosave failed for {}: {}", snapshot.bookId(), e.getMessage());
            return false;
        }
    }

    private void restoreDirty(State failed) {
        state.updateAndGet(current -> current.generation() != failed.generation()
                ? current
                : new State(current.collectionId(), current.bookId(), current.totalTextLength(),
                        current.latest(), true, current.generation()));
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
        state.updateAndGet(previous -> new State(null, null, 0L, null, false, previous.generation() + 1));
    }

    private record State(String collectionId, String bookId, long totalTextLength, ReaderPosition latest,
                         boolean dirty, long generation) {
        private static State empty() { return new State(null, null, 0L, null, false, 0L); }
    }
}
