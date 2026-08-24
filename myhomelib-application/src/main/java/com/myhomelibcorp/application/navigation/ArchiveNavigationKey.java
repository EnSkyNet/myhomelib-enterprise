package com.myhomelibcorp.application.navigation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Stable, UI-neutral key for a physical archive container.
 *
 * <p>The encoded form is safe to store in navigation history. A NUL separator
 * is used before Base64 encoding because it cannot occur in filesystem paths.</p>
 */
public record ArchiveNavigationKey(String collectionRoot, String archivePath) {

    public ArchiveNavigationKey {
        collectionRoot = collectionRoot == null ? "" : collectionRoot;
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        if (archivePath.isBlank()) {
            throw new IllegalArgumentException("archivePath cannot be blank");
        }
    }

    public String encode() {
        String payload = collectionRoot + '\0' + archivePath;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static ArchiveNavigationKey decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Archive navigation key cannot be blank");
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = payload.indexOf('\0');
            if (separator < 0) {
                throw new IllegalArgumentException("Archive navigation key is malformed");
            }
            return new ArchiveNavigationKey(payload.substring(0, separator), payload.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid archive navigation key", e);
        }
    }
}
