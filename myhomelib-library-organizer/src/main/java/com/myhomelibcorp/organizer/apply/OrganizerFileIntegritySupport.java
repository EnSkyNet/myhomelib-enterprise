package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;

import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Shared race-aware file ownership and SHA-256 verification for organizer commit/rollback. */
final class OrganizerFileIntegritySupport {
    private static final int HASH_BUFFER_BYTES = 64 * 1024;
    private static final System.Logger LOG = System.getLogger(OrganizerFileIntegritySupport.class.getName());

    private OrganizerFileIntegritySupport() {}

    static void checkCancelled(java.util.function.BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("Library organization cancelled");
    }

    static String sha256(Path path) throws Exception {
        return sha256(path, () -> false);
    }

    static String sha256(Path path, java.util.function.BooleanSupplier cancelled) throws Exception {
        return sha256(path, cancelled, bytes -> { });
    }

    static String sha256(Path path, java.util.function.BooleanSupplier cancelled,
                         java.util.function.LongConsumer byteProgress) throws Exception {
        checkCancelled(cancelled);
        java.util.Objects.requireNonNull(byteProgress, "byteProgress");
        Path safe = OrganizerPathSafety.canonicalExistingFile(path, "Organizer hash source");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        long started = System.nanoTime();
        long lastProgress = started;
        long hashedBytes = 0L;
        try (InputStream input = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            while (true) {
                checkCancelled(cancelled);
                int read = input.read(buffer);
                if (read < 0) break;
                checkCancelled(cancelled);
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    hashedBytes += read;
                    byteProgress.accept(hashedBytes);
                }
                long now = System.nanoTime();
                if (now - lastProgress >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                    LOG.log(System.Logger.Level.INFO,
                            "ORGANIZER_HASH_PROGRESS file={0} hashedBytes={1} elapsedMs={2}",
                            safe.getFileName(), hashedBytes,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - started));
                    lastProgress = now;
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void requireSafeRegularFile(Path file, String message) {
        try {
            OrganizerPathSafety.canonicalExistingFile(file, message);
        } catch (Exception unsafe) {
            throw new IllegalStateException(message + ": unsafe or missing file " + file, unsafe);
        }
    }

    static void verifyRequiredHash(Path file, String expected, String message) throws Exception {
        requireSafeRegularFile(file, message);
        if (expected == null || expected.isBlank() || !expected.equalsIgnoreCase(sha256(file))) {
            throw new IllegalStateException(message + ": " + file);
        }
    }

    static void verifyOptionalHash(Path file, String expected, String message) throws Exception {
        requireSafeRegularFile(file, message);
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(sha256(file))) {
            throw new IllegalStateException(message + ": " + file);
        }
    }

    static boolean matchesRequiredHash(Path file, String expected) {
        try {
            verifyRequiredHash(file, expected, "Source safety/hash verification failed");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void copyAndVerify(Path source, Path target, String expected) throws Exception {
        copyAndVerify(source, target, expected, () -> false);
    }

    static void copyAndVerify(Path source, Path target, String expected,
                              java.util.function.BooleanSupplier cancelled) throws Exception {
        copyAndVerify(source, target, expected, cancelled, (stage, bytes) -> { });
    }

    static void copyAndVerify(Path source, Path target, String expected,
                              java.util.function.BooleanSupplier cancelled,
                              java.util.function.BiConsumer<String, Long> milestone) throws Exception {
        checkCancelled(cancelled);
        // Stream SHA-256, independent target SHA-256 and the final cancellation
        // checkpoint now share the same CREATE_NEW ownership/cleanup scope.
        OrganizerPathSafety.copyFreshFileVerified(source, target, "Organizer verified copy",
                cancelled, expected, milestone);
    }

    static boolean removeOwnedFileIfPresent(Path path, String expectedHash, String role) throws Exception {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        removeOwnedFile(path, expectedHash, role);
        return true;
    }

    /**
     * Removes only the exact expected bytes. The file is first detached and then re-verified so a
     * racing path replacement is never deleted. Changed detached bytes are restored when possible.
     */
    static void removeOwnedFile(Path path, String expectedHash, String role) throws Exception {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalStateException(role + ": expected SHA-256 is missing");
        }
        Path safe = OrganizerPathSafety.canonicalExistingFile(path, role);
        verifyRequiredHash(safe, expectedHash, role + " pre-detach hash mismatch");
        Path parent = safe.getParent();
        if (parent == null) throw new IllegalStateException(role + ": path has no parent: " + safe);

        Path evidence = OrganizerPathSafety.canonicalForCreation(
                parent.resolve(".mhl-remove-" + UUID.randomUUID() + ".tmp"),
                role + " evidence");
        if (Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(evidence.toString());
        }
        Files.move(safe, evidence);
        try {
            verifyRequiredHash(evidence, expectedHash, role + " post-detach hash mismatch");
        } catch (Exception changed) {
            try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(evidence, path);
                } else {
                    changed.addSuppressed(new IllegalStateException(
                            role + ": original path was occupied; detached evidence retained at " + evidence));
                }
            } catch (Exception restore) {
                changed.addSuppressed(restore);
            }
            throw changed;
        }
        Files.delete(evidence);
    }
}
