package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Low-level file publication primitives shared by higher-level fail-closed boundaries.
 * Callers supply their own path validators/preparers so layer-specific safety contracts and
 * diagnostics remain authoritative while the byte-moving/hash algorithms have one implementation.
 */
public final class SafeFilePrimitives {
    private static final AtomicLong HARDLINK_FALLBACKS = new AtomicLong();

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

    /**
     * Publish a fully prepared file without replacing any existing destination.
     *
     * Hard links provide an atomic, complete-file publication where supported. Some
     * Windows volumes (for example certain removable/network filesystems) reject
     * CREATE_LINK with ERROR_INVALID_FUNCTION. On *that specific failure only*, a
     * CREATE_NEW-style copy is used: unlike a check-then-rename, this never replaces
     * a concurrently created destination. The fallback may expose a still-copying
     * new file; callers MUST verify its digest before updating catalogs or removing
     * originals. Sources and temporary files remain untouched by this method.
     *
     * @return true for atomic hard-link publication; false for the safe copy fallback
     */
    public static boolean publishFreshNoReplace(Path prepared, Path target, String role) throws IOException {
        return publishFreshNoReplace(prepared, target, role, Files::createLink);
    }

    @FunctionalInterface
    interface HardLinkCreator {
        Path create(Path link, Path existing) throws IOException;
    }

    // Package-scoped seam lets tests reproduce Windows' ERROR_INVALID_FUNCTION on Linux.
    static boolean publishFreshNoReplace(Path prepared, Path target, String role,
                                         HardLinkCreator hardLinkCreator) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try {
            hardLinkCreator.create(target, prepared);
            return true;
        } catch (FileSystemException error) {
            if (!isUnsupportedHardLink(error)) throw error;
            logHardLinkFallback(role, target, error.getReason());
        } catch (UnsupportedOperationException error) {
            logHardLinkFallback(role, target, error.getClass().getSimpleName());
        }
        // Without REPLACE_EXISTING, Files.copy creates a new destination and refuses
        // an occupied path. A normal Files.move could replace a racing destination
        // on some platforms, which is unacceptable for an organizer's book library.
        // This deliberately chooses integrity over atomic visibility on volumes
        // where hard links are unsupported.
        Files.copy(prepared, target, LinkOption.NOFOLLOW_LINKS);
        return false;
    }

    private static void logHardLinkFallback(String role, Path target, String reason) {
        long count = HARDLINK_FALLBACKS.incrementAndGet();
        // Large libraries can publish many thousands of books; keep the diagnostic
        // without generating a warning for every single file on an exFAT volume.
        if (count == 1 || count % 1000 == 0) {
            System.getLogger(SafeFilePrimitives.class.getName()).log(System.Logger.Level.WARNING,
                    "FILE_PUBLICATION_HARDLINK_UNSUPPORTED count={0} role={1} target={2} "
                            + "fallback=CREATE_NEW_COPY reason={3}", count, role, target, reason);
        }
    }

    private static boolean isUnsupportedHardLink(FileSystemException failure) {
        String reason = failure.getReason();
        if (reason == null) return false;
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("incorrect function")
                || normalized.contains("invalid function")
                || normalized.contains("неверная функция")
                || normalized.contains("неправильна функція")
                || normalized.contains("operation not supported")
                || normalized.contains("not supported")
                || normalized.contains("function not implemented")
                || normalized.contains("операція не підтримується")
                || normalized.contains("операция не поддерживается");
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
