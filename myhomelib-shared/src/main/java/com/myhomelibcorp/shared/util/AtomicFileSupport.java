package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/**
 * Cross-platform file publication helper.
 *
 * <p>The legacy implementation used {@code REPLACE_EXISTING}, which was atomic against partial
 * writes but not against symlink/TOCTOU replacement of the destination. The current implementation
 * is fail-closed: parent components must not be links/special files and an existing destination is
 * replaced only when its bytes are unchanged from the snapshot taken at the beginning of the
 * operation.</p>
 */
public final class AtomicFileSupport {
    private AtomicFileSupport() { }

    @FunctionalInterface
    public interface WriterBody {
        void write(Writer output) throws IOException;
    }

    /**
     * Atomically publish large text incrementally without materializing the entire output
     * in a String. Caller writes only to an owned private staging file, never the target.
     */
    public static void writeWithWriterReplacing(
            Path target, Charset charset, WriterBody body) throws IOException {
        if (target == null) throw new IllegalArgumentException("target cannot be null");
        if (charset == null) throw new IllegalArgumentException("charset cannot be null");
        if (body == null) throw new IllegalArgumentException("body cannot be null");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Target has no parent directory: " + target);
        createDirectoriesNoLinks(parent, "atomic-write parent");
        String expectedTargetHash = snapshotTarget(absolute, "atomic-write target");

        Path staging = Files.createTempFile(parent, ".mhl-write-", ".part");
        String stagingHash = null;
        try {
            try (Writer output = Files.newBufferedWriter(staging, charset,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                body.write(output);
            }
            stagingHash = sha256(staging, "atomic-write staging");
            publishReplacingIfUnchanged(staging, absolute, expectedTargetHash, "atomic write");
        } finally {
            // A failed writer may have produced only a partial staged file. Remove it
            // only if it is still the same safe file with the bytes just inspected.
            if (stagingHash == null && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    stagingHash = sha256(staging, "failed atomic-write staging");
                } catch (IOException unsafeStaging) {
                    // Cannot prove ownership after the writer failed: preserve evidence.
                    stagingHash = null;
                }
            }
            cleanupOwnedStaging(staging, stagingHash);
        }
    }

    public static void writeStringReplacing(Path target, CharSequence content, Charset charset) throws IOException {
        writeWithWriterReplacing(target, charset, output -> output.append(content == null ? "" : content));
    }

    /**
     * Compatibility API for callers that already created a staging file. This snapshots the target
     * at invocation time and protects the commit itself against symlink/TOCTOU replacement. Callers
     * that need to protect the whole generation window should take their snapshot before generation
     * and use a higher-level publication primitive.
     */
    public static Path moveReplacing(Path source, Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        String expectedTargetHash = snapshotTarget(absolute, "atomic-move target");
        publishReplacingIfUnchanged(source, absolute, expectedTargetHash, "atomic move");
        return absolute;
    }

    private static void publishReplacingIfUnchanged(
            Path source, Path target, String expectedTargetHash, String role) throws IOException {
        Path safeSource = requireSafeRegularFile(source, role + " source");
        String sourceHash = sha256(safeSource, role + " source");
        Path detached = null;
        boolean published = false;
        IOException failure = null;
        try {
            if (expectedTargetHash != null) {
                detached = detachIfOwned(target, expectedTargetHash, role + " target");
            } else {
                Path safeTarget = prepareTarget(target, role + " target");
                if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(safeTarget.toString());
                }
            }
            moveNoReplace(safeSource, target, role + " publication");
            if (!sourceHash.equalsIgnoreCase(sha256(target, role + " published target"))) {
                throw new IOException(role + " published bytes differ from staged bytes: " + target);
            }
            published = true;
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            if (!published && detached != null && Files.exists(detached, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveNoReplace(detached, target, role + " rollback");
                    detached = null;
                } catch (IOException restoreFailure) {
                    if (failure != null) failure.addSuppressed(restoreFailure);
                }
            }
            if (published && detached != null) {
                try {
                    removeIfOwned(detached, expectedTargetHash, role + " old-target cleanup");
                } catch (IOException ignored) {
                    // Publication is already verified. Preserve unknown evidence rather than deleting it.
                }
            }
        }
    }

    private static String snapshotTarget(Path target, String role) throws IOException {
        Path safe = prepareTarget(target, role);
        if (!Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) return null;
        return sha256(safe, role + " snapshot");
    }

    private static Path prepareTarget(Path target, String role) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + absolute);
        createDirectoriesNoLinks(parent, role + " parent");
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(absolute, role);
        }
        return absolute;
    }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
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

    public static void requireSafeDirectory(Path directory, String role) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException(role + " contains a symbolic link: " + directory);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isOther()) {
            throw new IOException(role + " is not a safe directory: " + directory);
        }
    }

    /** Validates every existing component of a directory path without following links/reparse entries. */
    public static Path requireSafeDirectoryPath(Path directory, String role) throws IOException {
        if (directory == null) throw new IllegalArgumentException("directory cannot be null");
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

    public static Path requireSafeRegularFile(Path file, String role) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(role + " does not exist: " + absolute);
            }
            if (Files.isSymbolicLink(current)) {
                throw new IOException(role + " contains a symbolic link: " + current);
            }
            BasicFileAttributes attrs = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) {
                throw new IOException(role + " contains a special/reparse entry: " + current);
            }
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(role + " is not a regular file: " + absolute);
        }
        return absolute;
    }

    private static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        return SafeFilePrimitives.moveNoReplace(
                source, target, role, AtomicFileSupport::requireSafeRegularFile, AtomicFileSupport::prepareTarget);
    }

    private static Path detachIfOwned(Path target, String expectedHash, String role) throws IOException {
        Path safeTarget = requireSafeRegularFile(target, role + " target");
        String before = sha256(safeTarget, role + " target snapshot");
        if (!expectedHash.equalsIgnoreCase(before)) {
            throw new IOException(role + " changed before detach: " + safeTarget);
        }
        Path evidence = safeTarget.getParent().resolve(
                "." + safeTarget.getFileName() + ".mhl-evidence-" + UUID.randomUUID() + ".tmp");
        moveNoReplace(safeTarget, evidence, role + " detach");
        String after = sha256(evidence, role + " detached target");
        if (!expectedHash.equalsIgnoreCase(after)) {
            try {
                if (!Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    moveNoReplace(evidence, safeTarget, role + " changed-target restore");
                }
            } catch (IOException restoreFailure) {
                IOException failure = new IOException(role + " changed during detach; evidence preserved at " + evidence);
                failure.addSuppressed(restoreFailure);
                throw failure;
            }
            throw new IOException(role + " changed during detach");
        }
        return evidence;
    }

    private static void removeIfOwned(Path target, String expectedHash, String role) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path detached = detachIfOwned(target, expectedHash, role);
        Files.delete(detached);
    }

    private static String sha256(Path file, String role) throws IOException {
        return SafeFilePrimitives.sha256(file, role, AtomicFileSupport::requireSafeRegularFile);
    }

    private static void cleanupOwnedStaging(Path staging, String expectedHash) {
        if (staging == null || expectedHash == null || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            removeIfOwned(staging, expectedHash, "atomic-write staging cleanup");
        } catch (IOException ignored) {
            // Preserve unknown replacement instead of deleting it.
        }
    }
}
