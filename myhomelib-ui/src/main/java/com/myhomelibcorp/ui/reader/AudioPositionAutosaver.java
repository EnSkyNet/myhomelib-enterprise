package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.audio.AudioPosition;

/** Crash-loss-bounded audiobook position autosave facade. */
final class AudioPositionAutosaver implements AutoCloseable {
    private final PositionAutosaveCoordinator<AudioPosition, Double> coordinator;

    AudioPositionAutosaver(NewReaderPersistenceService persistence) {
        coordinator = new PositionAutosaveCoordinator<>(
                "Audiobook",
                "audiobook-position-autosave",
                persistence::saveAudioPosition);
    }

    void start(String collectionId, String bookId) {
        coordinator.start(collectionId, bookId, 0.0);
    }

    void mark(AudioPosition position, double percent) {
        coordinator.mark(position, Double.isFinite(percent) ? Math.max(0.0, Math.min(100.0, percent)) : 0.0);
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
