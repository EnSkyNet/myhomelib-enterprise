package com.myhomelibcorp.reader.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        pb.redirectErrorStream(true);
        Process process = pb.start();
        CapturedProcess captured = waitForCaptured(process, probeTimeout, MAX_DIAGNOSTIC_BYTES, "ffprobe");
        if (captured.exitCode() != 0) throw new IOException("ffprobe failed: " + compact(captured.output()));
        return parseProbe(source, captured.output());
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
                    chapters.add(new AudioChapter(chapterTitle == null || chapterTitle.isBlank() ? "Chapter " + index : chapterTitle,
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
            Process process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            return waitForCaptured(process, Duration.ofSeconds(2), 4096, command).exitCode() == 0;
        } catch (Exception ignored) { return false; }
    }

    private static Path requireLocalAudio(Path value) throws IOException {
        if (value == null) throw new IOException("Audio path is required");
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException("Audio file is unavailable: " + path);
        return path;
    }

    private static CapturedProcess waitForCaptured(Process process, Duration timeout, int maxBytes, String label) throws IOException {
        AtomicReference<String> output = new AtomicReference<>("");
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                output.set(readBounded(in, maxBytes));
            } catch (IOException error) {
                readFailure.set(error);
            }
        }, "myhomelib-" + label + "-output");
        drainer.setDaemon(true);
        drainer.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                joinQuietly(drainer);
                throw new IOException(label + " timed out");
            }
            joinQuietly(drainer);
            IOException readError = readFailure.get();
            if (readError != null) throw new IOException(label + " output read failed", readError);
            return new CapturedProcess(process.exitValue(), output.get());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            Thread.currentThread().interrupt();
            throw new IOException(label + " interrupted", interrupted);
        }
    }

    private static void joinQuietly(Thread thread) throws InterruptedException {
        thread.join(1000);
        if (thread.isAlive()) thread.interrupt();
    }

    /** Drains the complete stream to avoid child-process pipe deadlocks while retaining only a bounded prefix. */
    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, maxBytes));
        byte[] buffer = new byte[4096];
        int captured = 0;
        while (true) {
            int read = in.read(buffer);
            if (read < 0) break;
            int accepted = Math.min(read, Math.max(0, maxBytes - captured));
            if (accepted > 0) {
                out.write(buffer, 0, accepted);
                captured += accepted;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
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

    private record CapturedProcess(int exitCode, String output) { }

    private record ProcessPlayback(Process process) implements Playback {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void stop() {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                if (!process.waitFor(700, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
