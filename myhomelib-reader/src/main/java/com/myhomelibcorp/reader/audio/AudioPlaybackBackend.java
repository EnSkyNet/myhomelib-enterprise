package com.myhomelibcorp.reader.audio;

import java.io.IOException;
import java.nio.file.Path;

/** Optional OS-process audio backend. Reader state remains usable even when the backend is unavailable. */
public interface AudioPlaybackBackend {
    boolean available();
    AudioTrack probe(Path path) throws IOException;
    Playback start(Path path, long startMillis, double rate) throws IOException;

    interface Playback extends AutoCloseable {
        boolean isAlive();
        void stop();
        @Override default void close() { stop(); }
    }
}
