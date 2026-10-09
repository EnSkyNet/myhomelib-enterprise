package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        Path marker = marker(collectionId);
        try {
            RecoveryFileSupport.validateParent(marker, "catalog-update recovery marker parent");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
            AtomicFileSupport.requireSafeRegularFile(marker, "catalog-update recovery marker");
            return true;
        } catch (IOException unsafe) {
            throw new IllegalStateException("Unsafe catalog-update recovery marker path: " + marker, unsafe);
        }
    }

    public static void markPending(String collectionId, String operationId) throws IOException {
        Path marker = marker(collectionId).toAbsolutePath().normalize();
        RecoveryFileSupport.validateParent(marker, "catalog-update recovery marker parent");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            AtomicFileSupport.requireSafeRegularFile(marker, "catalog-update recovery marker");
            throw new IOException("Catalog update pending marker already exists: " + marker);
        }
        String body = "operation=" + safe(operationId) + System.lineSeparator()
                + "startedAt=" + Instant.now() + System.lineSeparator();
        Files.writeString(marker, body, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        AtomicFileSupport.requireSafeRegularFile(marker, "catalog-update recovery marker");
    }

    public static void clear(String collectionId) throws IOException {
        RecoveryFileSupport.safeDelete(marker(collectionId), "catalog-update recovery marker cleanup");
        RecoveryFileSupport.safeDelete(checkpoint(collectionId), "catalog-update recovery checkpoint cleanup");
    }

    public static void deleteMarkerOnly(String collectionId) throws IOException {
        RecoveryFileSupport.safeDelete(marker(collectionId), "catalog-update recovery marker cleanup");
    }





    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\r', '_').replace('\n', '_');
    }
}
