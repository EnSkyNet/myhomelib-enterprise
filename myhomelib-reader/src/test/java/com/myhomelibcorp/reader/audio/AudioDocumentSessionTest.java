package com.myhomelibcorp.reader.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDocumentSessionTest {
    @Test void opensMultipleTracksAndComputesAbsoluteProgress() throws Exception {
        Path a = Files.createTempFile("audio-a", ".mp3");
        Path b = Files.createTempFile("audio-b", ".mp3");
        FakeBackend backend = new FakeBackend(10_000L, 20_000L);
        try (AudioDocumentSession session = AudioDocumentSession.open(List.of(a, b), backend)) {
            session.seek(new AudioPosition(5_000L, 1, 0));
            assertThat(session.totalDurationMillis()).isEqualTo(30_000L);
            assertThat(session.absoluteMillis(session.currentPosition())).isEqualTo(15_000L);
            assertThat(session.progressPercent()).isEqualTo(50.0);
        } finally { Files.deleteIfExists(a); Files.deleteIfExists(b); }
    }

    @Test void seekClampsTrackAndPositionSafely() throws Exception {
        Path a = Files.createTempFile("audio", ".m4b");
        try (AudioDocumentSession session = AudioDocumentSession.open(List.of(a), new FakeBackend(4_000L))) {
            session.seek(new AudioPosition(99_000L, 99, 99));
            assertThat(session.currentPosition()).isEqualTo(new AudioPosition(4_000L, 0, 0));
        } finally { Files.deleteIfExists(a); }
    }

    @Test void validatesPlaybackRateAndRestartsBackendWhenPlaying() throws Exception {
        Path a = Files.createTempFile("audio", ".mp3");
        FakeBackend backend = new FakeBackend(60_000L);
        try (AudioDocumentSession session = AudioDocumentSession.open(List.of(a), backend)) {
            session.play();
            session.setRate(1.5);
            assertThat(session.rate()).isEqualTo(1.5);
            assertThat(backend.startedRates).containsExactly(1.0, 1.5);
            assertThatThrownBy(() -> session.setRate(2.1)).isInstanceOf(IllegalArgumentException.class);
        } finally { Files.deleteIfExists(a); }
    }

    @Test void pauseStopsOwnedPlaybackProcess() throws Exception {
        Path a = Files.createTempFile("audio", ".mp3");
        FakeBackend backend = new FakeBackend(60_000L);
        try (AudioDocumentSession session = AudioDocumentSession.open(List.of(a), backend)) {
            session.play();
            session.pause();
            assertThat(session.isPlaying()).isFalse();
            assertThat(backend.lastPlayback.stopped).isTrue();
        } finally { Files.deleteIfExists(a); }
    }

    private static final class FakeBackend implements AudioPlaybackBackend {
        private final long[] durations;
        private int probeIndex;
        private final List<Double> startedRates = new ArrayList<>();
        private FakePlayback lastPlayback;
        FakeBackend(long... durations) { this.durations = durations; }
        @Override public boolean available() { return true; }
        @Override public AudioTrack probe(Path path) {
            long duration = durations[Math.min(probeIndex++, durations.length - 1)];
            return new AudioTrack(path, path.getFileName().toString(), duration, List.of());
        }
        @Override public Playback start(Path path, long startMillis, double rate) throws IOException {
            startedRates.add(rate); lastPlayback = new FakePlayback(); return lastPlayback;
        }
    }
    private static final class FakePlayback implements AudioPlaybackBackend.Playback {
        boolean stopped;
        @Override public boolean isAlive() { return !stopped; }
        @Override public void stop() { stopped = true; }
    }
}
