package com.myhomelibcorp.application.catalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Stable logical identity helpers. Remote sync must use the collection id, never a downloaded temp path. */
public final class CatalogSourceIdentity {
    private CatalogSourceIdentity() { }

    public static String remoteCollection(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("collectionId is required for remote catalog identity");
        }
        return "remote-collection:" + collectionId.trim();
    }

    public static String localInpx(Path file, Path root) {
        if (file == null) throw new IllegalArgumentException("file is required");
        Path source = file.toAbsolutePath().normalize();
        if (root != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (source.startsWith(normalizedRoot)) {
                String relative = normalizedRoot.relativize(source).toString().replace('\\', '/');
                return "local-inpx:" + relative;
            }
        }
        return "local-inpx:" + source.toString().replace('\\', '/');
    }

    public static String stableId(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        return UUID.nameUUIDFromBytes(("catalog-source:" + sourceKey.trim())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
