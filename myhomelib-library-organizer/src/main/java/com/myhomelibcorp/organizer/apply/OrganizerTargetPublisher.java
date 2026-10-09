package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;

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

    void publish(Path staged, Path target) throws Exception {
        Path safeStaged = OrganizerPathSafety.canonicalExistingFile(staged, "Organizer staged output");
        Path safeTarget = OrganizerPathSafety.canonicalForCreation(target, "Organizer target");
        Path parent = safeTarget.getParent();
        if (parent == null) throw new IllegalArgumentException("Target has no parent: " + safeTarget);
        OrganizerPathSafety.createDirectoriesNoLinks(parent, "Organizer target directory");
        if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeTarget.toString());
        }

        String expectedHash = sha256(safeStaged);
        Path temp = safeTarget.resolveSibling("." + safeTarget.getFileName() + ".mhl-tmp-" + UUID.randomUUID());
        boolean published = false;
        try {
            OrganizerPathSafety.copyFreshFile(safeStaged, temp, "Organizer destination temporary copy");
            verifyRequiredHash(temp, expectedHash, "Destination temporary copy hash mismatch");
            // Do not replace an existing target: a concurrent creator must win and make this operation fail closed.
            Files.move(temp, safeTarget);
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
            } catch (Exception ignored) {
                // The operation workspace/recovery evidence remains authoritative; do not mask the primary outcome.
            }
        }
    }

    private static void recordRetainedTarget(Exception failure, Path target, Exception cleanup) {
        failure.addSuppressed(new IllegalStateException(
                "Retained target for recovery because rollback ownership verification failed: " + target, cleanup));
    }
}
