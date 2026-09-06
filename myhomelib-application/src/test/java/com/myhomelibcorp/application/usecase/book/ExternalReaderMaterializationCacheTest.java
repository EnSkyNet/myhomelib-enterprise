package com.myhomelibcorp.application.usecase.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalReaderMaterializationCacheTest {
    @TempDir Path temp;

    @Test
    void startupDeletesCrashLeftovers() throws Exception {
        Path cacheDir = temp.resolve("external-reader");
        Files.createDirectories(cacheDir);
        Path stale = Files.writeString(cacheDir.resolve("book-crash.fb2"), "private book");

        ExternalReaderMaterializationCache cache = cache(cacheDir, 1024, Duration.ofHours(1));
        cache.initialize();

        assertThat(stale).doesNotExist();
    }

    @Test
    void enforcesAgeAndSizeBoundsWithoutDeletingActiveLease() throws Exception {
        Path cacheDir = temp.resolve("external-reader");
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T10:00:00Z"));
        ExternalReaderMaterializationCache cache = new ExternalReaderMaterializationCache(
                cacheDir, 8, 8, Duration.ofMinutes(30), clock);
        cache.initialize();

        ExternalReaderMaterializationCache.Lease active = cache.materialize(bytes("12345678"), "fb2");
        Path activePath = active.path();
        clock.advance(Duration.ofHours(2));
        cache.cleanupInactive();
        assertThat(activePath).exists();
        assertThatThrownBy(() -> cache.materialize(bytes("x"), "fb2"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("cache");

        active.close();
        assertThat(activePath).doesNotExist();
        try (ExternalReaderMaterializationCache.Lease next = cache.materialize(bytes("x"), "fb2")) {
            assertThat(next.path()).exists();
        }

        Path oldUnmanaged = Files.writeString(cacheDir.resolve("book-old.fb2"), "old");
        Files.setLastModifiedTime(oldUnmanaged, FileTime.from(clock.instant().minus(Duration.ofHours(1))));
        cache.cleanupInactive();
        assertThat(oldUnmanaged).doesNotExist();
    }

    @Test
    void trackedProcessKeepsFileUntilProcessExit() throws Exception {
        ExternalReaderMaterializationCache cache = cache(temp.resolve("external-reader"), 1024, Duration.ofHours(1));
        cache.initialize();
        ExternalReaderMaterializationCache.Lease lease = cache.materialize(bytes("book"), "fb2");
        Path path = lease.path();
        ControlledProcess process = new ControlledProcess();

        lease.retainUntil(process);
        lease.close();
        assertThat(path).exists();

        process.complete(0);
        assertThat(path).doesNotExist();
    }

    @Test
    void untrackedDesktopStyleLeaseSurvivesSessionButIsRemovedOnNextStartup() throws Exception {
        Path cacheDir = temp.resolve("external-reader");
        ExternalReaderMaterializationCache first = cache(cacheDir, 1024, Duration.ofHours(1));
        first.initialize();
        ExternalReaderMaterializationCache.Lease lease = first.materialize(bytes("book"), "epub");
        Path path = lease.path();
        lease.keepUntilNextStartup();
        first.close();
        assertThat(path).exists();

        ExternalReaderMaterializationCache nextStartup = cache(cacheDir, 1024, Duration.ofHours(1));
        nextStartup.initialize();
        assertThat(path).doesNotExist();
    }

    private ExternalReaderMaterializationCache cache(Path dir, long maxBytes, Duration age) {
        return new ExternalReaderMaterializationCache(dir, maxBytes, maxBytes, age, Clock.systemUTC());
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class ControlledProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;
        private volatile int code;
        void complete(int code) { this.code = code; this.alive = false; exit.complete(this); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { if (alive) exit.join(); return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { try { exit.get(timeout, unit); return true; } catch (Exception e) { return false; } }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return code; }
        @Override public void destroy() { complete(0); }
        @Override public Process destroyForcibly() { complete(0); return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return exit; }
    }
}
