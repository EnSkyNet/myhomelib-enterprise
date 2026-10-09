package com.myhomelibcorp.epub.optimizer.fs;

import com.myhomelibcorp.shared.util.NoLinkPathSafety;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** EPUB-optimizer facade over the shared no-link filesystem boundary. */
public final class EpubPathSafety {
    private EpubPathSafety() { }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        return NoLinkPathSafety.createDirectories(directory, role);
    }

    public static Path createPrivateWorkspace(Path parent, String prefix, String role) throws IOException {
        return NoLinkPathSafety.createPrivateWorkspace(parent, prefix, role);
    }

    public static void cleanupPrivateWorkspace(Path workspace, String role) throws IOException {
        NoLinkPathSafety.cleanupPrivateWorkspace(workspace, role);
    }

    public static Path prepareTarget(Path target, String role) throws IOException {
        return NoLinkPathSafety.prepareTarget(target, role);
    }

    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        return NoLinkPathSafety.openFreshOutputStream(target, role);
    }

    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        return NoLinkPathSafety.copyFreshFile(source, target, role);
    }

    public static Path requireSafeRegularFile(Path file, String role) throws IOException {
        return NoLinkPathSafety.requireRegularFile(file, role);
    }

    public static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        return NoLinkPathSafety.moveNoReplace(source, target, role);
    }

    public static void rejectLinkLikeExistingComponents(Path path, String role) throws IOException {
        NoLinkPathSafety.rejectUnsafeExistingComponents(path, role);
    }
}
