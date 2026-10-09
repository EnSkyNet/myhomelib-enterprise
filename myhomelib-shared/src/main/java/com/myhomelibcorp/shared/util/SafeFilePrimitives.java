package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Low-level file publication primitives shared by higher-level fail-closed boundaries.
 * Callers supply their own path validators/preparers so layer-specific safety contracts and
 * diagnostics remain authoritative while the byte-moving/hash algorithms have one implementation.
 */
public final class SafeFilePrimitives {
    private SafeFilePrimitives() { }

    @FunctionalInterface
    public interface PathResolver {
        Path resolve(Path path, String role) throws IOException;
    }

    public static Path moveNoReplace(
            Path source,
            Path target,
            String role,
            PathResolver regularFileResolver,
            PathResolver targetPreparer) throws IOException {
        Path safeSource = regularFileResolver.resolve(source, role + " source");
        Path safeTarget = targetPreparer.resolve(target, role + " target");
        if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeTarget.toString());
        }
        Files.move(safeSource, safeTarget);
        return regularFileResolver.resolve(safeTarget, role + " target");
    }

    public static String sha256(Path file, String role, PathResolver regularFileResolver) throws IOException {
        Path safe = regularFileResolver.resolve(file, role);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
