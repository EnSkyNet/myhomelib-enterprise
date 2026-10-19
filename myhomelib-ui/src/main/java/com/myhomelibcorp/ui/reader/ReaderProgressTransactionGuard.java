package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interprocess barrier covering the ENTIRE Reader progress transition:
 * SQLite read -> journal prepare -> database commit -> conditional acknowledgement.
 *
 * <p>The narrower ReaderRecoveryFileGuard only protects individual journal I/O.
 * This separate lock namespace prevents a second cooperating Reader process from
 * committing a stale snapshot while the first process is between journal and DB.
 * Every operation touching this transition MUST take this guard first; then
 * acquire individual journal locks. Never nest transaction guards.</p>
 *
 * <p>This is a cooperative lock for Reader writers, not a database-wide CAS.
 * Sync/import and external SQLite writers remain outside its guarantee.</p>
 */
final class ReaderProgressTransactionGuard {
    private static final int STRIPES = 256;
    private static final ReentrantLock[] JVM_LOCKS = new ReentrantLock[STRIPES];
    static { for (int i = 0; i < STRIPES; i++) JVM_LOCKS[i] = new ReentrantLock(); }
    private final Path directory;
    private final long timeoutNanos;

    ReaderProgressTransactionGuard() {
        this(AppPaths.dataDir().resolve("reader-progress-transactions"), TimeUnit.SECONDS.toNanos(8));
    }

    ReaderProgressTransactionGuard(Path directory) {
        this(directory, TimeUnit.SECONDS.toNanos(8));
    }

    ReaderProgressTransactionGuard(Path directory, long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeoutNanos must be positive");
        this.directory = directory.toAbsolutePath().normalize();
        this.timeoutNanos = timeoutNanos;
    }

    @FunctionalInterface
    interface IOAction<T> { T run() throws IOException; }

    <T> T withLock(String collectionId, String bookId, IOAction<T> action) throws IOException {
        if (collectionId == null || collectionId.isBlank() || bookId == null || bookId.isBlank()) {
            throw new IOException("Reader progress lock requires collection and book identity");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((collectionId + "\0" + bookId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        int stripe = Byte.toUnsignedInt(digest[0]);
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            if (!JVM_LOCKS[stripe].tryLock(timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new IOException("Reader progress transaction lock timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Reader progress transaction lock interrupted");
        }
        try {
            AtomicFileSupport.createDirectoriesNoLinks(directory, "Reader transaction lock directory");
            Path file = directory.resolve(String.format(".reader-progress-%02x.lock", stripe));
            // Stable, never-unlinked lock files keep all processes in the same
            // lock domain. NOFOLLOW_LINKS prevents symlink redirection.
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFileSupport.requireSafeRegularFile(file, "Reader transaction lock");
                for (;;) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Reader progress lock interrupted");
                    }
                    try {
                        FileLock acquired = channel.tryLock();
                        if (acquired != null) {
                            try (FileLock ignored = acquired) { return action.run(); }
                        }
                    } catch (OverlappingFileLockException heldInJvm) {
                        if (System.nanoTime() >= deadline) {
                            throw new IOException("Reader progress transaction lock timed out (held in this JVM)", heldInJvm);
                        }
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new IOException("Reader progress transaction lock timed out");
                    }
                    try { Thread.sleep(20); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Reader progress lock interrupted");
                    }
                }
            }
        } finally {
            JVM_LOCKS[stripe].unlock();
        }
    }
}
