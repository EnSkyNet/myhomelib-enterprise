package com.myhomelibcorp.reader.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegAudioPlaybackBackendRealSmokeTest {
    @Test void probesRealMp3AndM4bAndStartsRealFfplay() throws Exception {
        Assumptions.assumeTrue(commandWorks("ffmpeg", "-version"));
        FfmpegAudioPlaybackBackend backend = new FfmpegAudioPlaybackBackend("ffprobe", "ffplay", Duration.ofSeconds(5));
        Assumptions.assumeTrue(backend.available());
        Path dir = Files.createTempDirectory("mhl-real-audio");
        Path mp3 = dir.resolve("sample.mp3");
        Path m4b = dir.resolve("sample.m4b");
        try {
            run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.25", "-c:a", "libmp3lame", "-y", mp3.toString());
            run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=550:duration=0.25", "-c:a", "aac", "-f", "ipod", "-y", m4b.toString());
            assertThat(backend.probe(mp3).durationMillis()).isPositive();
            assertThat(backend.probe(m4b).durationMillis()).isPositive();
            try (AudioPlaybackBackend.Playback playback = backend.start(mp3, 0L, 1.0)) {
                playback.stop();
                assertThat(playback.isAlive()).isFalse();
            }
        } finally {
            Files.deleteIfExists(mp3); Files.deleteIfExists(m4b); Files.deleteIfExists(dir);
        }
    }

    private static boolean commandWorks(String... argv) {
        try { Process p = new ProcessBuilder(argv).redirectErrorStream(true).start(); return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0; }
        catch (Exception e) { return false; }
    }
    private static void run(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) p.destroyForcibly();
        assertThat(finished).isTrue(); assertThat(p.exitValue()).isZero();
    }
}
