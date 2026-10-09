package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.shared.util.NoLinkPathSafety;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** Organizer-specific facade over the canonical no-link filesystem safety boundary. */
public final class OrganizerPathSafety {
    private OrganizerPathSafety() { }

    public static Path canonicalExistingDirectory(Path root, String role) throws IOException {
        return NoLinkPathSafety.requireDirectory(root, role);
    }

    public static Path canonicalForCreation(Path path, String role) throws IOException {
        return NoLinkPathSafety.canonicalForCreation(path, role);
    }

    public static Path canonicalExistingFile(Path file, String role) throws IOException {
        return NoLinkPathSafety.requireRegularFile(file, role);
    }

    public static void rejectLinkLikeExistingComponents(Path path, String role) throws IOException {
        NoLinkPathSafety.rejectUnsafeExistingComponents(path, role);
    }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        return NoLinkPathSafety.createDirectories(directory, role);
    }

    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        return NoLinkPathSafety.openFreshOutputStream(target, role);
    }

    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        return NoLinkPathSafety.copyFreshFile(source, target, role);
    }

    public static void requireUnderRoot(Path root, Path child, String role) throws IOException {
        NoLinkPathSafety.requireUnderRoot(root, child, role);
    }

    public static Path nearestExistingAncestor(Path path) throws IOException {
        return NoLinkPathSafety.nearestExistingAncestor(path);
    }
}
