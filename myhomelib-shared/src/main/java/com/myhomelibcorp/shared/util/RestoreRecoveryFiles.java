package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Durable filesystem protocol for crash-safe replacement of a collection SQLite database.
 *
 * <p>The {@code .restore.pending} marker is the transaction intent for a user Restore. It is
 * created only after the incoming database has been staged and validated, and it is removed only
 * after the restored database plus dependent metadata/statistics/index work has completed. This
 * makes {@code .restore.previous} unambiguous: it is a rollback source only while the pending marker
 * exists. If the marker is already gone, a leftover previous file is merely cleanup residue from a
 * successfully committed Restore.</p>
 */
public final class RestoreRecoveryFiles {
    private RestoreRecoveryFiles() { }

    public static Path staged(Path targetDatabase) {
        return sibling(targetDatabase, ".restore.tmp");
    }

    public static Path previous(Path targetDatabase) {
        return sibling(targetDatabase, ".restore.previous");
    }

    public static Path pending(Path targetDatabase) {
        return sibling(targetDatabase, ".restore.pending");
    }

    public static boolean isPending(Path targetDatabase) {
        Path marker = pending(targetDatabase);
        try {
            RecoveryFileSupport.validateParent(marker, "restore recovery marker parent");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
            AtomicFileSupport.requireSafeRegularFile(marker, "restore recovery marker");
            return true;
        } catch (IOException unsafe) {
            throw new IllegalStateException("Unsafe restore recovery marker path: " + marker, unsafe);
        }
    }

    public static void markPending(Path targetDatabase) throws IOException {
        Path marker = pending(targetDatabase).toAbsolutePath().normalize();
        RecoveryFileSupport.validateParent(marker, "restore recovery marker parent");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            AtomicFileSupport.requireSafeRegularFile(marker, "restore recovery marker");
            throw new IOException("Restore pending marker already exists: " + marker);
        }
        Files.writeString(marker,
                "startedAt=" + Instant.now() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        AtomicFileSupport.requireSafeRegularFile(marker, "restore recovery marker");
    }

    public static void clearPending(Path targetDatabase) throws IOException {
        Path marker = pending(targetDatabase);
        RecoveryFileSupport.safeDelete(marker, "restore recovery marker cleanup");
        RecoveryFileSupport.safeDelete(marker.resolveSibling(marker.getFileName() + ".tmp"), "restore recovery temp-marker cleanup");
    }





    private static Path sibling(Path target, String suffix) {
        if (target == null || target.getFileName() == null) {
            throw new IllegalArgumentException("Target database path must have a file name");
        }
        return target.resolveSibling(target.getFileName() + suffix);
    }
}
