package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes a recovery journal's read/verify/write/ack sequence across Reader
 * instances and operating-system processes. The 256 reusable lock files bound
 * disk metadata even for large libraries. Lock files must never be unlinked:
 * deleting one while a process holds the lock could split the lock domain.
 * This is not a SQLite transaction lock; competing database saves require their
 * own concurrency policy.
 */
final class ReaderRecoveryFileGuard {
    private static final ReentrantLock[] JVM_LOCKS = new ReentrantLock[256];
    static { for (int i = 0; i < JVM_LOCKS.length; i++) JVM_LOCKS[i] = new ReentrantLock(); }
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);

    private ReaderRecoveryFileGuard() { }

    @FunctionalInterface
    interface IOAction<T> { T run() throws IOException; }

    static <T> T withLock(Path pendingFile, IOAction<T> action) throws IOException {
        // Pending filenames are SHA-256 hex followed by '.pending', so the first
        // byte defines a stable stripe shared by all processes and instances.
        String name = pendingFile.getFileName().toString();
        int stripe;
        try { stripe = Integer.parseInt(name.substring(0, 2), 16); }
        catch (RuntimeException invalid) { throw new IOException("Invalid recovery journal file name", invalid); }
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        try {
            if (!JVM_LOCKS[stripe].tryLock(3, TimeUnit.SECONDS)) {
                throw new IOException("Reader recovery JVM lock unavailable (3 seconds)");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Reader recovery JVM lock interrupted");
        }
        try {
            Path parent = pendingFile.toAbsolutePath().normalize().getParent();
            AtomicFileSupport.createDirectoriesNoLinks(parent, "Reader journal lock directory");
            Path lockFile = parent.resolve(String.format(".reader-recovery-%02x.lock", stripe));
            // NOFOLLOW_LINKS refuses symlink lockfiles. Keep these lockfiles
            // permanently; reopening, not deleting, preserves lock identity.
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFileSupport.requireSafeRegularFile(lockFile, "Reader recovery lock");
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Reader recovery lock interrupted");
                    }
                    try {
                        FileLock acquired = channel.tryLock();
                        if (acquired != null) {
                            try (FileLock ignored = acquired) { return action.run(); }
                        }
                    } catch (OverlappingFileLockException busyInJvm) {
                        // Another instance owns this lock in the same JVM.
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new IOException("Reader recovery lock unavailable (3 seconds)");
                    }
                    try { Thread.sleep(20); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Reader recovery lock interrupted");
                    }
                }
            }
        } finally {
            JVM_LOCKS[stripe].unlock();
        }
    }
}
