package com.myhomelibcorp.converter.toolchain.workspace;

import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

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
            // Stream files in post-order rather than sorting every workspace path in memory.
            // The default walker does not follow nested symlinks.
            Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); }
                    catch (IOException ignored) {
                        // Preserve best-effort cleanup: locked files may survive on Windows.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    // Do not follow or retry an inaccessible filesystem entry.
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    if (failure == null) {
                        try { Files.deleteIfExists(directory); }
                        catch (IOException ignored) {
                            // A directory with locked children must be retained.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Fail closed: leave the workspace behind for a later safe cleanup/restart rather than
            // traversing a path whose filesystem identity is no longer trustworthy.
        }
    }
}
