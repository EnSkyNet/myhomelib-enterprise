package com.myhomelibcorp.reader.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Stateful audiobook session independent of JavaFX. Supports multi-track resume, rate and sleep timer. */
public final class AudioDocumentSession implements AutoCloseable {
    private final AudioPlaybackBackend backend;
    private final List<AudioTrack> tracks;
    private AudioPlaybackBackend.Playback playback;
    private AudioPosition position = AudioPosition.start();
    private long playStartedNanos;
    private long playStartedMillis;
    private double rate = 1.0;
    private boolean playing;
    private long sleepDeadlineNanos;

    private AudioDocumentSession(AudioPlaybackBackend backend, List<AudioTrack> tracks) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.tracks = List.copyOf(tracks);
        if (this.tracks.isEmpty()) throw new IllegalArgumentException("At least one audio track is required");
    }

    public static AudioDocumentSession open(List<Path> paths, AudioPlaybackBackend backend) throws IOException {
        if (paths == null || paths.isEmpty()) throw new IOException("No audiobook tracks");
        java.util.ArrayList<AudioTrack> tracks = new java.util.ArrayList<>();
        for (Path path : paths) tracks.add(backend.probe(path));
        return new AudioDocumentSession(backend, tracks);
    }

    public List<AudioTrack> tracks() { return tracks; }
    public boolean backendAvailable() { return backend.available(); }
    public boolean isPlaying() { refreshNaturalEnd(); return playing; }
    public double rate() { return rate; }

    public AudioPosition currentPosition() {
        refreshNaturalEnd();
        if (!playing) return normalized(position);
        long elapsedNanos = Math.max(0L, System.nanoTime() - playStartedNanos);
        long advanced = Math.round((elapsedNanos / 1_000_000.0) * rate);
        return normalized(new AudioPosition(playStartedMillis + advanced, position.trackIndex(), chapterAt(position.trackIndex(), playStartedMillis + advanced)));
    }

    public void play() throws IOException {
        refreshNaturalEnd();
        if (playing) return;
        AudioPosition current = normalized(position);
        AudioTrack track = tracks.get(current.trackIndex());
        playback = backend.start(track.path(), current.millis(), rate);
        playStartedMillis = current.millis();
        playStartedNanos = System.nanoTime();
        position = current;
        playing = true;
    }

    public void pause() {
        if (!playing) return;
        position = currentPositionWithoutNaturalEnd();
        stopProcess();
        playing = false;
    }

    public void seek(AudioPosition target) throws IOException {
        boolean resume = playing;
        if (playing) pause();
        position = normalized(target == null ? AudioPosition.start() : target);
        if (resume) play();
    }

    public void setRate(double newRate) throws IOException {
        if (newRate < 0.5 || newRate > 2.0) throw new IllegalArgumentException("Playback rate must be between 0.5 and 2.0");
        boolean resume = playing;
        if (playing) pause();
        rate = newRate;
        if (resume) play();
    }

    public void setSleepTimer(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) sleepDeadlineNanos = 0L;
        else sleepDeadlineNanos = System.nanoTime() + duration.toNanos();
    }

    public boolean tickSleepTimer() {
        if (sleepDeadlineNanos > 0 && System.nanoTime() >= sleepDeadlineNanos) {
            sleepDeadlineNanos = 0L;
            pause();
            return true;
        }
        refreshNaturalEnd();
        return false;
    }

    public long totalDurationMillis() { return tracks.stream().mapToLong(AudioTrack::durationMillis).sum(); }
    public long absoluteMillis(AudioPosition pos) {
        AudioPosition p = normalized(pos);
        long before = 0L;
        for (int i = 0; i < p.trackIndex(); i++) before += tracks.get(i).durationMillis();
        return before + p.millis();
    }
    public double progressPercent() {
        long total = totalDurationMillis();
        return total <= 0 ? 0.0 : Math.min(100.0, absoluteMillis(currentPosition()) * 100.0 / total);
    }

    private void refreshNaturalEnd() {
        if (!playing) return;
        AudioPosition now = currentPositionWithoutNaturalEnd();
        AudioTrack track = tracks.get(now.trackIndex());
        boolean processEnded = playback != null && !playback.isAlive();
        if (now.millis() < track.durationMillis() && !processEnded) return;
        stopProcess();
        playing = false;
        if (now.trackIndex() + 1 < tracks.size()) {
            position = new AudioPosition(0L, now.trackIndex() + 1, 0);
            try { play(); } catch (IOException ignored) { playing = false; }
        } else {
            position = new AudioPosition(track.durationMillis(), now.trackIndex(), Math.max(0, track.chapters().size() - 1));
        }
    }

    private AudioPosition currentPositionWithoutNaturalEnd() {
        if (!playing) return normalized(position);
        long elapsedNanos = Math.max(0L, System.nanoTime() - playStartedNanos);
        long advanced = Math.round((elapsedNanos / 1_000_000.0) * rate);
        long millis = playStartedMillis + advanced;
        return normalized(new AudioPosition(millis, position.trackIndex(), chapterAt(position.trackIndex(), millis)));
    }

    private AudioPosition normalized(AudioPosition raw) {
        int trackIndex = Math.max(0, Math.min(raw.trackIndex(), tracks.size() - 1));
        AudioTrack track = tracks.get(trackIndex);
        long millis = Math.max(0L, Math.min(raw.millis(), track.durationMillis()));
        return new AudioPosition(millis, trackIndex, chapterAt(trackIndex, millis));
    }

    private int chapterAt(int trackIndex, long millis) { return tracks.get(trackIndex).chapterIndexAt(millis); }
    private void stopProcess() { if (playback != null) { playback.stop(); playback = null; } }

    @Override public void close() { pause(); }

}
