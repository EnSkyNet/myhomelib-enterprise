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
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;

    private OrganizerFileIntegritySupport() {}

    static String sha256(Path path) throws Exception {
        Path safe = OrganizerPathSafety.canonicalExistingFile(path, "Organizer hash source");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
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
        verifyRequiredHash(source, expected, "Source hash mismatch before copy");
        OrganizerPathSafety.copyFreshFile(source, target, "Organizer verified copy");
        verifyRequiredHash(target, expected, "Staged hash mismatch");
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
                parent.resolve("." + safe.getFileName() + ".mhl-remove-" + UUID.randomUUID() + ".tmp"),
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
