package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration contract for cooperating Reader writers, no actual library access. */
class ReaderProgressTransactionGuardTest {
    @TempDir Path dir;

    @Test
    void wholeReadModifyCommitSequenceCannotOverlapAcrossInstances() throws Exception {
        var a = new ReaderProgressTransactionGuard(dir);
        var b = new ReaderProgressTransactionGuard(dir);
        var inside = new AtomicInteger();
        var maximum = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int w = 0; w < 4; w++) {
                var guard = w % 2 == 0 ? a : b;
                futures.add(executor.submit(() -> {
                    for (int n = 0; n < 30; n++) {
                        try {
                            guard.withLock("collection", "book", () -> {
                                int active = inside.incrementAndGet();
                                maximum.accumulateAndGet(active, Math::max);
                                try { Thread.sleep(1); }
                                catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(ex);
                                } finally { inside.decrementAndGet(); }
                                return null;
                            });
                        } catch (IOException failure) { throw new IllegalStateException(failure); }
                    }
                }));
            }
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isEqualTo(1);
    }

    @Test
    void failClosedOnTimeoutAndPreserveOriginalData() throws Exception {
        var fast = new ReaderProgressTransactionGuard(dir, TimeUnit.MILLISECONDS.toNanos(150));
        var slow = new ReaderProgressTransactionGuard(dir);
        Path marker = dir.resolve("test-sqlite-marker");
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                try {
                    slow.withLock("collection", "book", () -> {
                        started.countDown();
                        try { Thread.sleep(450); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                        return null;
                    });
                } catch (IOException failure) { throw new IllegalStateException(failure); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> fast.withLock("collection", "book", () -> {
                Files.writeString(marker, "incorrect-write");
                return null;
            })).isInstanceOf(IOException.class);
            assertThat(Files.exists(marker)).isFalse();
            writer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void doesNotFollowSymlinkLockDirectory() throws Exception {
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Path linked = dir.resolve("linked");
        try { Files.createSymbolicLink(linked, outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) { return; }
        var guard = new ReaderProgressTransactionGuard(linked);
        assertThatThrownBy(() -> guard.withLock("collection", "book", () -> null))
                .isInstanceOf(IOException.class);
    }
}
