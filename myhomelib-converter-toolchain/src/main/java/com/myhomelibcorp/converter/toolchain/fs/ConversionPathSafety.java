package com.myhomelibcorp.converter.toolchain.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;

/** No-link filesystem boundary for converter staging and publication paths. */
public final class ConversionPathSafety {
    private ConversionPathSafety() { }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(current, role);
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException race) {
                    requireSafeDirectory(current, role);
                }
            }
        }
        return absolute;
    }

    /** Creates an operation-private directory under an already safe parent. */
    public static Path createPrivateWorkspace(Path parent, String prefix, String role) throws IOException {
        Path safeParent = createDirectoriesNoLinks(parent, role + " parent");
        Path workspace = Files.createTempDirectory(safeParent, prefix).toAbsolutePath().normalize();
        requireSafeDirectoryPath(workspace, role);
        return workspace;
    }

    /** Cleans only a private workspace created by this process; symbolic links are deleted as entries, never followed. */
    public static void cleanupPrivateWorkspace(Path workspace, String role) throws IOException {
        if (workspace == null || !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is no longer a safe private directory: " + workspace);
        }
        requireSafeDirectoryPath(workspace, role);
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public static Path requireSafeDirectoryPath(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(role + " does not exist: " + absolute);
            }
            requireSafeDirectory(current, role);
        }
        return absolute;
    }

    public static Path prepareTarget(Path target, String role) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + absolute);
        createDirectoriesNoLinks(parent, role + " parent");
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(absolute)) throw new IOException(role + " is a symbolic link: " + absolute);
            BasicFileAttributes attrs = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isOther()) throw new IOException(role + " is not a safe regular file: " + absolute);
        }
        return absolute;
    }

    public static Path requireSafeRegularFile(Path file, String role) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(role + " does not exist: " + absolute);
            }
            if (Files.isSymbolicLink(current)) throw new IOException(role + " contains a symbolic link: " + current);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) throw new IOException(role + " contains a reparse/special filesystem entry: " + current);
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException(role + " is not a regular file: " + absolute);
        return absolute;
    }



    /** Opens a fresh file without following or replacing a racing target. */
    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        Path absolute = prepareTarget(target, role);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        try {
            return Files.newOutputStream(absolute, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException race) {
            throw new IOException(role + " appeared while opening a fresh output: " + absolute, race);
        }
    }

    /** Copies bytes into a fresh no-link target; a racing target is never replaced. */
    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        Path safeSource = requireSafeRegularFile(source, role + " source");
        Path safeTarget = prepareTarget(target, role + " target");
        if (safeSource.equals(safeTarget)) return safeTarget;
        try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = openFreshOutputStream(safeTarget, role + " target")) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) if (read > 0) out.write(buffer, 0, read);
            out.flush();
        }
        return requireSafeRegularFile(safeTarget, role + " target");
    }

    /** Moves a safe regular file only when the destination is absent; a racing destination is never replaced. */
    public static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        Path safeSource = requireSafeRegularFile(source, role + " source");
        Path safeTarget = prepareTarget(target, role + " target");
        if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(safeTarget.toString());
        }
        Files.move(safeSource, safeTarget);
        return requireSafeRegularFile(safeTarget, role + " target");
    }

    private static void requireSafeDirectory(Path directory, String role) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException(role + " contains a symbolic link: " + directory);
        BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isOther()) throw new IOException(role + " contains a reparse/special filesystem entry: " + directory);
        if (!attrs.isDirectory()) throw new IOException(role + " component is not a directory: " + directory);
    }
}
