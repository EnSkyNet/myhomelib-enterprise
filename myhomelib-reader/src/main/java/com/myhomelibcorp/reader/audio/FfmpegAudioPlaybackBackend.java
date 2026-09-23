package com.myhomelibcorp.reader.audio;

import com.myhomelibcorp.shared.util.ProcessExecutionSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ffprobe/ffplay backend. Commands are launched with argv-only ProcessBuilder, never through a shell. */
public final class FfmpegAudioPlaybackBackend implements AudioPlaybackBackend {
    private static final int MAX_DIAGNOSTIC_BYTES = 256 * 1024;
    private final String ffprobe;
    private final String ffplay;
    private final Duration probeTimeout;

    public FfmpegAudioPlaybackBackend() { this("ffprobe", "ffplay", Duration.ofSeconds(8)); }

    public FfmpegAudioPlaybackBackend(String ffprobe, String ffplay, Duration probeTimeout) {
        this.ffprobe = requireCommand(ffprobe);
        this.ffplay = requireCommand(ffplay);
        this.probeTimeout = probeTimeout == null || probeTimeout.isNegative() || probeTimeout.isZero()
                ? Duration.ofSeconds(8) : probeTimeout;
    }

    @Override public boolean available() {
        return commandAvailable(ffprobe) && commandAvailable(ffplay);
    }

    @Override public AudioTrack probe(Path path) throws IOException {
        Path source = requireLocalAudio(path);
        ProcessBuilder pb = new ProcessBuilder(ffprobe, "-v", "error", "-show_chapters",
                "-show_entries", "format=duration", "-of", "default=noprint_wrappers=0", source.toString());
        ProcessExecutionSupport.Result captured;
        try {
            captured = ProcessExecutionSupport.run(pb, null, probeTimeout, MAX_DIAGNOSTIC_BYTES);
        } catch (ProcessExecutionSupport.ProcessTimeoutException timeout) {
            throw new IOException("ffprobe timed out", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("ffprobe interrupted", interrupted);
        }
        if (captured.exitCode() != 0) {
            String diagnostic = captured.stderrText().isBlank() ? captured.stdoutText() : captured.stderrText();
            throw new IOException("ffprobe failed: " + compact(diagnostic));
        }
        return parseProbe(source, captured.stdoutText());
    }

    @Override public Playback start(Path path, long startMillis, double rate) throws IOException {
        Path source = requireLocalAudio(path);
        double safeRate = Math.max(0.5, Math.min(2.0, rate));
        List<String> argv = new ArrayList<>(List.of(ffplay, "-nodisp", "-autoexit", "-loglevel", "error"));
        if (startMillis > 0) argv.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", startMillis / 1000.0)));
        if (Math.abs(safeRate - 1.0) > 0.0001) argv.addAll(List.of("-af", String.format(Locale.ROOT, "atempo=%.3f", safeRate)));
        argv.add(source.toString());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        return new ProcessPlayback(process);
    }

    static AudioTrack parseProbe(Path source, String output) throws IOException {
        double durationSeconds = -1;
        List<AudioChapter> chapters = new ArrayList<>();
        Double chapterStart = null;
        Double chapterEnd = null;
        String chapterTitle = null;
        boolean inChapter = false;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if ("[CHAPTER]".equals(line)) {
                inChapter = true; chapterStart = null; chapterEnd = null; chapterTitle = null; continue;
            }
            if ("[/CHAPTER]".equals(line)) {
                if (chapterStart != null && chapterEnd != null) {
                    int index = chapters.size() + 1;
                    chapters.add(new AudioChapter(chapterTitle == null || chapterTitle.isBlank() ? "#" + index : chapterTitle,
                            secondsToMillis(chapterStart), secondsToMillis(chapterEnd)));
                }
                inChapter = false; continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (inChapter) {
                if ("start_time".equals(key)) chapterStart = decimal(value);
                else if ("end_time".equals(key)) chapterEnd = decimal(value);
                else if ("TAG:title".equalsIgnoreCase(key)) chapterTitle = value;
            } else if ("duration".equals(key)) {
                durationSeconds = decimal(value);
            }
        }
        if (!(durationSeconds > 0)) {
            durationSeconds = chapters.stream().mapToLong(AudioChapter::endMillis).max().orElse(0L) / 1000.0;
        }
        if (!(durationSeconds > 0)) throw new IOException("ffprobe returned no usable duration for " + source.getFileName());
        long durationMillis = secondsToMillis(durationSeconds);
        return new AudioTrack(source, source.getFileName().toString(), durationMillis, chapters);
    }

    private boolean commandAvailable(String command) {
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                    List.of(command, "-version"), null, Duration.ofSeconds(2), 4096);
            return result.exitCode() == 0;
        } catch (Exception ignored) { return false; }
    }

    private static Path requireLocalAudio(Path value) throws IOException {
        if (value == null) throw new IOException("Audio path is required");
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Audio file is unavailable: " + path);
        return path;
    }

    private static double decimal(String value) {
        try { return Double.parseDouble(value.trim()); }
        catch (RuntimeException ignored) { return -1; }
    }
    private static long secondsToMillis(double seconds) { return Math.max(0L, Math.round(seconds * 1000.0)); }
    private static String compact(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
    private static String requireCommand(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Command is required");
        return command.trim();
    }


    private record ProcessPlayback(Process process) implements Playback {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void stop() {
            if (!process.isAlive()) return;
            ProcessExecutionSupport.terminate(process);
        }
    }
}
