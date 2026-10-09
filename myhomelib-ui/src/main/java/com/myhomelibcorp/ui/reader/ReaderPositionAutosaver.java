package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;

/** Crash-loss-bounded text-reader position autosave facade. */
final class ReaderPositionAutosaver implements AutoCloseable {
    private final PositionAutosaveCoordinator<ReaderPosition, Long> coordinator;

    ReaderPositionAutosaver(NewReaderPersistenceService persistence) {
        coordinator = new PositionAutosaveCoordinator<>(
                "Reader",
                "reader-position-autosave",
                persistence::savePosition);
    }

    void start(String collectionId, String bookId, long totalTextLength) {
        coordinator.start(collectionId, bookId, Math.max(0L, totalTextLength));
    }

    void mark(ReaderPosition position) {
        coordinator.mark(position);
    }

    boolean flush() {
        return coordinator.flush();
    }

    boolean closeAndFlush() {
        return coordinator.closeAndFlush();
    }

    @Override
    public void close() {
        coordinator.close();
    }
}
