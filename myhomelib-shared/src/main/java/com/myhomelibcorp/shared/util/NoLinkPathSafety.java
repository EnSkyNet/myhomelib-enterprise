package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical fail-closed filesystem boundary for application-managed paths.
 *
 * <p>Every existing path component is inspected without following links. Symbolic links,
 * reparse/special entries and non-directory parent components are rejected before a caller
 * reads, writes, moves or recursively cleans a managed path. This class deliberately contains
 * only path/byte primitives; feature-specific publication policies remain in their owning
 * modules.</p>
 */
public final class NoLinkPathSafety {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;

    private NoLinkPathSafety() { }

    public static Path createDirectories(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryComponent(current, role);
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException race) {
                    requireDirectoryComponent(current, role);
                }
            }
        }
        return absolute;
    }

    public static Path requireDirectory(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(role + " does not exist: " + absolute);
            }
            requireDirectoryComponent(current, role);
        }
        return absolute;
    }

    public static Path createPrivateWorkspace(Path parent, String prefix, String role) throws IOException {
        Path safeParent = createDirectories(parent, role + " parent");
        Path workspace = Files.createTempDirectory(safeParent, prefix).toAbsolutePath().normalize();
        return requireDirectory(workspace, role);
    }

    /**
     * Deletes an operation-private directory without following links. A workspace that was
     * replaced by a link/file is rejected rather than recursively traversed.
     */
    public static void cleanupPrivateWorkspace(Path workspace, String role) throws IOException {
        if (workspace == null || !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        Path safeWorkspace = requireDirectory(workspace, role);
        // Stream deletion instead of collecting and sorting every descendant in memory.
        // walkFileTree does not follow symbolic links unless explicitly requested.
        Files.walkFileTree(safeWorkspace, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Validates an existing prefix without creating missing suffix components. */
    public static Path canonicalForCreation(Path path, String role) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            rejectUnsafeExistingComponents(absolute, role);
            BasicFileAttributes attrs = Files.readAttributes(
                    absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
                throw new IOException(role + " is a special filesystem entry: " + absolute);
            }
            validatePlatformLength(absolute, role);
            return absolute;
        }
        Path existing = nearestExistingAncestor(absolute);
        requireDirectory(existing, role + " existing ancestor");
        Path result = existing.resolve(existing.relativize(absolute)).normalize();
        validatePlatformLength(result, role);
        return result;
    }

    public static Path nearestExistingAncestor(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) throw new IOException("No existing filesystem ancestor for path: " + path);
        return current;
    }

    public static void requireUnderRoot(Path root, Path child, String role) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(child, "child");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IOException(role + " escapes configured root: " + normalizedChild);
        }
    }

    public static Path prepareTarget(Path target, String role) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + absolute);
        createDirectories(parent, role + " parent");
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(absolute, role);
        }
        return absolute;
    }

    public static Path requireRegularFile(Path file, String role) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        rejectUnsafeExistingComponents(absolute, role);
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " does not exist: " + absolute);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isOther()) {
            throw new IOException(role + " is not a safe regular file: " + absolute);
        }
        return absolute;
    }

    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        Path absolute = prepareTarget(target, role);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        try {
            return Files.newOutputStream(absolute, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException race) {
            throw new IOException(role + " appeared while opening a fresh output: " + absolute, race);
        }
    }

    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        Path safeSource = requireRegularFile(source, role + " source");
        Path safeTarget = prepareTarget(target, role + " target");
        if (safeSource.equals(safeTarget)) {
            throw new IOException(role + " cannot copy a file onto itself: " + safeSource);
        }
        // CREATE_NEW still protects against replacing an existing target. Once the
        // output has been created, remember its identity so a failed copy can remove
        // only its own incomplete artifact, never a file swapped in by another actor.
        Object createdFileKey = null;
        boolean targetOpened = false;
        try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = openFreshOutputStream(safeTarget, role + " target")) {
            targetOpened = true;
            createdFileKey = Files.readAttributes(safeTarget, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException | RuntimeException failure) {
            if (targetOpened) {
                try {
                    // A null fileKey offers no reliable ownership proof: retain the
                    // incomplete artifact as diagnostic evidence rather than risk
                    // deleting somebody else's file.
                    if (createdFileKey != null && Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes current = Files.readAttributes(safeTarget,
                                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (current.isRegularFile() && createdFileKey.equals(current.fileKey())) {
                            Files.delete(safeTarget);
                        }
                    }
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        return requireRegularFile(safeTarget, role + " target");
    }

    public static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        return SafeFilePrimitives.moveNoReplace(
                source, target, role, NoLinkPathSafety::requireRegularFile, NoLinkPathSafety::prepareTarget);
    }

    /** Checks every existing component of a path without requiring the final component to exist. */
    public static void rejectUnsafeExistingComponents(Path path, String role) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current)) {
                throw new IOException(role + " contains a symbolic link: " + current);
            }
            BasicFileAttributes attrs = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) {
                throw new IOException(role + " contains a special/reparse filesystem entry: " + current);
            }
        }
    }

    private static void validatePlatformLength(Path path, String role) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && path.toString().length() >= 32_000) {
            throw new IOException(role + " exceeds the Windows long-path safety ceiling: " + path);
        }
    }

    private static void requireDirectoryComponent(Path directory, String role) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException(role + " contains a symbolic link: " + directory);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isOther()) {
            throw new IOException(role + " contains a special/reparse filesystem entry: " + directory);
        }
        if (!attrs.isDirectory()) {
            throw new IOException(role + " component is not a directory: " + directory);
        }
    }
}
