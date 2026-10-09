package com.myhomelibcorp.converter.toolchain.fs;

import com.myhomelibcorp.shared.util.NoLinkPathSafety;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** Converter-facing facade over the shared no-link filesystem boundary. */
public final class ConversionPathSafety {
    private ConversionPathSafety() { }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        return NoLinkPathSafety.createDirectories(directory, role);
    }

    public static Path createPrivateWorkspace(Path parent, String prefix, String role) throws IOException {
        return NoLinkPathSafety.createPrivateWorkspace(parent, prefix, role);
    }

    public static void cleanupPrivateWorkspace(Path workspace, String role) throws IOException {
        NoLinkPathSafety.cleanupPrivateWorkspace(workspace, role);
    }

    public static Path requireSafeDirectoryPath(Path directory, String role) throws IOException {
        return NoLinkPathSafety.requireDirectory(directory, role);
    }

    public static Path prepareTarget(Path target, String role) throws IOException {
        return NoLinkPathSafety.prepareTarget(target, role);
    }

    public static Path requireSafeRegularFile(Path file, String role) throws IOException {
        return NoLinkPathSafety.requireRegularFile(file, role);
    }

    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        return NoLinkPathSafety.openFreshOutputStream(target, role);
    }

    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        return NoLinkPathSafety.copyFreshFile(source, target, role);
    }

    public static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        return NoLinkPathSafety.moveNoReplace(source, target, role);
    }
}
