package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shared fail-closed helpers for small recovery marker/checkpoint files. */
final class RecoveryFileSupport {
    private RecoveryFileSupport() { }

    static void safeDelete(Path path, String role) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        validateParent(absolute, role + " parent");
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return;
        Path safe = AtomicFileSupport.requireSafeRegularFile(absolute, role);
        Files.delete(safe);
    }

    static void validateParent(Path path, String role) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + path);
        AtomicFileSupport.createDirectoriesNoLinks(parent, role);
    }
}
