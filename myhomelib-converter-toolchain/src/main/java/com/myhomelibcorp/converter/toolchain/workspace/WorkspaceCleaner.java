package com.myhomelibcorp.converter.toolchain.workspace;

import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** Fail-closed cleanup for converter workspaces. Never traverses a replaced symlink/reparse path. */
public final class WorkspaceCleaner {
    private WorkspaceCleaner() { }

    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Path absolute = root.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(absolute)) {
                Files.deleteIfExists(absolute);
                return;
            }
            // Re-check every existing component immediately before recursive traversal. If a parent
            // was replaced after workspace creation, retain the workspace instead of risking a walk
            // through an attacker-controlled link/reparse point.
            ConversionPathSafety.requireSafeDirectoryPath(absolute, "Conversion workspace cleanup");
            try (var walk = Files.walk(absolute)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException ignored) {
                        // Best-effort cleanup only. The application must never trade path safety for
                        // aggressive deletion of temporary files.
                    }
                });
            }
        } catch (IOException ignored) {
            // Fail closed: leave the workspace behind for a later safe cleanup/restart rather than
            // traversing a path whose filesystem identity is no longer trustworthy.
        }
    }
}
