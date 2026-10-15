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
        boolean published = false;
        try {
            OrganizerPathSafety.copyFreshFile(safeStaged, temp, "Organizer destination temporary copy");
            verifyRequiredHash(temp, expectedHash, "Destination temporary copy hash mismatch");
            // Prefer atomic hard-link publication. FAT/exFAT and some other Windows
            // volumes reject it with ERROR_INVALID_FUNCTION; fall back to a fresh
            // no-replace copy rather than aborting the entire organization operation.
            // Final bytes are verified before the catalog/source is mutated.
            SafeFilePrimitives.publishFreshNoReplace(temp, safeTarget, "Organizer target");
            published = true;
            verifyRequiredHash(safeTarget, expectedHash, "Final target hash mismatch");
        } catch (Exception failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
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
                Files.deleteIfExists(temp);
            } catch (Exception cleanup) {
                LOG.log(System.Logger.Level.WARNING, "ORGANIZER_PUBLICATION_CLEANUP_FAILED temporary={0} error={1}",
                        temp.getFileName(), cleanup.getClass().getSimpleName());
            }
        }
    }

    private static void recordRetainedTarget(Exception failure, Path target, Exception cleanup) {
        failure.addSuppressed(new IllegalStateException(
                "Retained target for recovery because rollback ownership verification failed: " + target, cleanup));
    }
}
