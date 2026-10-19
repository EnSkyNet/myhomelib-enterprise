package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.shared.util.SafeFilePrimitives;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.matchesRequiredHash;
import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.removeOwnedFileIfPresent;
import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.sha256;
import static com.myhomelibcorp.organizer.apply.OrganizerFileIntegritySupport.verifyRequiredHash;

/** Fail-closed publication of one organizer staging artifact to its final target. */
final class OrganizerTargetPublisher {
    private static final System.Logger LOG = System.getLogger(OrganizerTargetPublisher.class.getName());

    void publish(Path staged, Path target) throws Exception {
        // Compatibility entry point for callers that have not pre-validated a staged artifact.
        publish(staged, target, sha256(staged));
    }

    /**
     * Publishes an artifact whose SHA-256 has already been verified during staging.
     * The temporary copy and final target are still hashed independently, so a
     * changed/replaced staged file cannot be published unnoticed.
     */
    void publish(Path staged, Path target, String expectedHash) throws Exception {
        publish(staged, target, expectedHash, () -> false);
    }

    void publish(Path staged, Path target, String expectedHash,
                 java.util.function.BooleanSupplier cancelled) throws Exception {
        OrganizerFileIntegritySupport.checkCancelled(cancelled);
        if (expectedHash == null || !expectedHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A validated SHA-256 digest is required for organizer publication");
        }
        Path safeStaged = OrganizerPathSafety.canonicalExistingFile(staged, "Organizer staged output");
        Path safeTarget;
        try {
            safeTarget = OrganizerPathSafety.canonicalForCreation(target, "Organizer target");
        } catch (IOException invalidPath) {
            // A competing publication may have created the final regular file
            // between path validation steps. It is a collision, not an unsafe
            // ancestor directory. Do not reinterpret links or directories here.
            Path existing = target.toAbsolutePath().normalize();
            if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(existing.toString());
            }
            throw invalidPath;
        }
        Path parent = safeTarget.getParent();
        if (parent == null) throw new IllegalArgumentException("Target has no parent: " + safeTarget);
        OrganizerPathSafety.createDirectoriesNoLinks(parent, "Organizer target directory");
        if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeTarget.toString());
        }

        Path temp = safeTarget.resolveSibling(".mhl-tmp-" + UUID.randomUUID());
        Object[] createdKey = new Object[1];
        boolean published = false;
        try {
            OrganizerPathSafety.copyFreshFileVerified(safeStaged, temp, "Organizer destination temporary copy",
                    cancelled, expectedHash, (stage, bytes) -> {
                        if ("OPENED".equals(stage)) {
                            try {
                                createdKey[0] = Files.readAttributes(temp, java.nio.file.attribute.BasicFileAttributes.class,
                                        LinkOption.NOFOLLOW_LINKS).fileKey();
                            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    });
            OrganizerFileIntegritySupport.checkCancelled(cancelled);
            // Prefer atomic hard-link publication. FAT/exFAT and some other Windows
            // volumes reject it with ERROR_INVALID_FUNCTION; fall back to a fresh
            // no-replace copy rather than aborting the entire organization operation.
            // Final bytes are verified before the catalog/source is mutated.
            SafeFilePrimitives.publishFreshNoReplace(temp, safeTarget, "Organizer target", cancelled);
            published = true;
            if (!expectedHash.equalsIgnoreCase(OrganizerFileIntegritySupport.sha256(safeTarget, cancelled))) {
                throw new IllegalStateException("Final target hash mismatch: " + safeTarget);
            }
            OrganizerFileIntegritySupport.checkCancelled(cancelled);
        } catch (Exception failure) {
            if (published) {
                if (matchesRequiredHash(safeTarget, expectedHash)) {
                    try {
                        removeOwnedFileIfPresent(safeTarget, expectedHash, "Organizer failed final publication rollback");
                    } catch (Exception cleanup) {
                        recordRetainedTarget(failure, safeTarget, cleanup);
                    }
                } else if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    failure.addSuppressed(new IllegalStateException(
                            "Published target changed after commit; retained it as recovery evidence: " + safeTarget));
                }
            }
            throw failure;
        } finally {
            try {
                cleanupTemporary(temp, createdKey[0], expectedHash);
            } catch (Exception cleanup) {
                LOG.log(System.Logger.Level.WARNING, "ORGANIZER_PUBLICATION_CLEANUP_FAILED temporary={0} error={1}",
                        temp.getFileName(), cleanup.getClass().getSimpleName());
            }
        }
    }

    private static void cleanupTemporary(Path temp, Object createdKey, String expectedHash) throws Exception {
        if (!Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) return;
        var attributes = Files.readAttributes(temp, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (createdKey == null || !attributes.isRegularFile() || !createdKey.equals(attributes.fileKey())) {
            LOG.log(System.Logger.Level.WARNING, "ORGANIZER_TEMP_IDENTITY_UNPROVEN_RETAINED temporary={0}", temp.getFileName());
            return;
        }
        verifyRequiredHash(temp, expectedHash, "Organizer temporary cleanup");
        // Revalidate the detached object as well: a replacement between the first
        // identity check and move must be restored/retained, never deleted.
        Path detached = temp.resolveSibling(".mhl-remove-" + UUID.randomUUID() + ".tmp");
        Files.move(temp, detached);
        try {
            var moved = Files.readAttributes(detached, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!moved.isRegularFile() || !createdKey.equals(moved.fileKey())) {
                throw new IllegalStateException("Organizer temporary identity changed during cleanup");
            }
            verifyRequiredHash(detached, expectedHash, "Organizer detached temporary cleanup");
        } catch (Exception changed) {
            try {
                if (!Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) Files.move(detached, temp);
            } catch (Exception restore) { changed.addSuppressed(restore); }
            throw changed;
        }
        Files.delete(detached);
    }

    private static void recordRetainedTarget(Exception failure, Path target, Exception cleanup) {
        failure.addSuppressed(new IllegalStateException(
                "Retained target for recovery because rollback ownership verification failed: " + target, cleanup));
    }
}
