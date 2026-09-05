package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return Files.isRegularFile(pending(targetDatabase));
    }

    public static void markPending(Path targetDatabase) throws IOException {
        Path marker = pending(targetDatabase);
        Path parent = marker.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = marker.resolveSibling(marker.getFileName() + ".tmp");
        try {
            Files.writeString(tmp,
                    "startedAt=" + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            AtomicFileSupport.moveReplacing(tmp, marker);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void clearPending(Path targetDatabase) throws IOException {
        Files.deleteIfExists(pending(targetDatabase));
        Files.deleteIfExists(pending(targetDatabase).resolveSibling(pending(targetDatabase).getFileName() + ".tmp"));
    }

    private static Path sibling(Path target, String suffix) {
        if (target == null || target.getFileName() == null) {
            throw new IllegalArgumentException("Target database path must have a file name");
        }
        return target.resolveSibling(target.getFileName() + suffix);
    }
}
