package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Durable marker/checkpoint bookkeeping for online catalog updates.
 *
 * <p>The marker is written only after a validated pre-update SQLite checkpoint exists. It is
 * removed only after the update has completed or a rollback has completed successfully. Therefore
 * a marker left after a JVM/OS crash is an unambiguous signal that the collection must be restored
 * from the checkpoint before it is opened for normal use.</p>
 */
public final class CatalogUpdateRecoveryFiles {
    private CatalogUpdateRecoveryFiles() { }

    public static Path checkpoint(String collectionId) {
        return AppPaths.catalogUpdateRecoveryCheckpoint(collectionId);
    }

    public static Path marker(String collectionId) {
        return AppPaths.catalogUpdateRecoveryMarker(collectionId);
    }

    public static boolean isPending(String collectionId) {
        return Files.isRegularFile(marker(collectionId));
    }

    public static void markPending(String collectionId, String operationId) throws IOException {
        Path marker = marker(collectionId);
        Files.createDirectories(marker.toAbsolutePath().getParent());
        Path tmp = marker.resolveSibling(marker.getFileName() + ".tmp");
        String body = "operation=" + safe(operationId) + System.lineSeparator()
                + "startedAt=" + Instant.now() + System.lineSeparator();
        try {
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            AtomicFileSupport.moveReplacing(tmp, marker);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void clear(String collectionId) throws IOException {
        Files.deleteIfExists(marker(collectionId));
        Files.deleteIfExists(checkpoint(collectionId));
    }

    public static void deleteMarkerOnly(String collectionId) throws IOException {
        Files.deleteIfExists(marker(collectionId));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\r', '_').replace('\n', '_');
    }
}
