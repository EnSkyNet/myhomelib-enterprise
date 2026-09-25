package com.myhomelibcorp.organizer.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;

/**
 * Filesystem-boundary checks shared by Organizer Analyze and Apply.
 * Existing path components are inspected with NOFOLLOW_LINKS so a symlink or
 * junction/reparse-like entry cannot silently redirect an operation outside
 * the user-selected roots.
 */
public final class OrganizerPathSafety {
    private OrganizerPathSafety() { }

    public static Path canonicalExistingDirectory(Path root, String role) throws IOException {
        Objects.requireNonNull(root, "root");
        Path absolute = root.toAbsolutePath().normalize();
        rejectLinkLikeExistingComponents(absolute, role);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(role + " is not an existing directory: " + absolute);
        }
        // All existing components were already validated without following links. Re-resolving
        // the pathname with toRealPath() would create a second parent-walk and reopen a TOCTOU
        // window where a checked parent could be replaced before canonicalization.
        return absolute;
    }

    /** Canonicalizes the existing prefix while preserving not-yet-created suffix components. */
    public static Path canonicalForCreation(Path root, String role) throws IOException {
        Objects.requireNonNull(root, "root");
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkLikeExistingComponents(absolute, role);
            BasicFileAttributes attrs = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
                throw new IOException(role + " is a special filesystem entry: " + absolute);
            }
            validatePlatformLength(absolute, role);
            return absolute;
        }
        Path existing = nearestExistingAncestor(absolute);
        rejectLinkLikeExistingComponents(existing, role);
        if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " existing ancestor is not a directory: " + existing);
        }
        Path suffix = existing.relativize(absolute);
        Path result = existing.resolve(suffix).normalize();
        validatePlatformLength(result, role);
        return result;
    }

    public static Path canonicalExistingFile(Path file, String role) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        rejectLinkLikeExistingComponents(absolute, role);
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is not a safe regular file: " + absolute);
        }
        return absolute;
    }

    public static void rejectLinkLikeExistingComponents(Path path, String role) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current)) {
                throw new IOException(role + " contains a symbolic link: " + current);
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // On Windows junctions/reparse points that are not exposed as symbolic links are commonly reported as "other".
            if (attrs.isOther()) {
                throw new IOException(role + " contains a reparse/special filesystem entry: " + current);
            }
        }
        validatePlatformLength(absolute, role);
    }

    /** Creates missing directory components one-by-one and never traverses an existing link/reparse entry. */
    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException(role + " contains a symbolic link: " + current);
                }
                BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isOther()) throw new IOException(role + " contains a reparse/special filesystem entry: " + current);
                if (!attrs.isDirectory()) throw new IOException(role + " component is not a directory: " + current);
            } else {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    // Re-check a component that appeared between exists() and createDirectory().
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw race;
                    BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isOther()) throw new IOException(role + " contains a reparse/special filesystem entry: " + current, race);
                }
            }
        }
        validatePlatformLength(absolute, role);
        return absolute;
    }


    /** Opens a fresh regular file without following or replacing a racing filesystem entry. */
    public static OutputStream openFreshOutputStream(Path target, String role) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = canonicalForCreation(target, role);
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + absolute);
        createDirectoriesNoLinks(parent, role + " parent");
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        try {
            return Files.newOutputStream(absolute, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException race) {
            throw new IOException(role + " appeared while opening a fresh output: " + absolute, race);
        }
    }

    /** Copies a safe regular file into a fresh no-link target; a racing target is never replaced. */
    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        Path safeSource = canonicalExistingFile(source, role + " source");
        Path safeTarget = canonicalForCreation(target, role + " target");
        if (safeSource.equals(safeTarget)) return safeTarget;
        try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = openFreshOutputStream(safeTarget, role + " target")) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        }
        return canonicalExistingFile(safeTarget, role + " target");
    }

    public static void requireUnderRoot(Path root, Path child, String role) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IOException(role + " escapes configured root: " + normalizedChild);
        }
    }

    public static Path nearestExistingAncestor(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) current = current.getParent();
        if (current == null) throw new IOException("No existing filesystem ancestor for path: " + path);
        return current;
    }

    private static void validatePlatformLength(Path path, String role) throws IOException {
        // NT path APIs have a hard practical ceiling near 32k UTF-16 code units even with long-path support enabled.
        if (isWindows() && path.toString().length() >= 32_000) {
            throw new IOException(role + " exceeds the Windows long-path safety ceiling: " + path);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
