package com.myhomelibcorp.application.util;

import com.myhomelibcorp.shared.util.NoLinkPathSafety;
import com.myhomelibcorp.shared.util.SafeFilePrimitives;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared fail-closed filesystem boundary for application-owned staging/publication paths.
 * Symbolic links and special/reparse-like entries are never traversed or replaced.
 */
public final class SafeFilePublication {
    private SafeFilePublication() { }

    /** Immutable fingerprint captured from one already-open no-follow file handle. */
    public record FileSnapshot(String sha256, long size) { }

    public static Path createDirectoriesNoLinks(Path directory, String role) throws IOException {
        return NoLinkPathSafety.createDirectories(directory, role);
    }

    public static Path createPrivateWorkspace(Path parent, String prefix, String role) throws IOException {
        return NoLinkPathSafety.createPrivateWorkspace(parent, prefix, role);
    }


    /**
     * Resolves an existing directory explicitly selected by the user. Unlike catalog-controlled
     * paths, an explicit destination is allowed to traverse a symlink/junction/reparse point:
     * the user chose that location and the canonical real directory becomes the persisted root.
     */
    public static Path requireUserSelectedDirectory(Path directory, String role) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path selected = directory.toAbsolutePath().normalize();
        if (!Files.exists(selected)) {
            throw new IOException(role + " does not exist: " + selected);
        }
        if (!Files.isDirectory(selected)) {
            throw new IOException(role + " is not a directory: " + selected);
        }
        Path real = selected.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException(role + " does not resolve to a directory: " + selected);
        }
        return real;
    }

    /**
     * Publishes an application-owned staged file into an explicitly user-selected directory.
     * Parent symlinks/reparse points are permitted by contract; the leaf itself is always created
     * fresh with NOFOLLOW_LINKS and is fingerprint-verified after publication.
     */
    public static FileSnapshot publishFreshToUserSelectedPath(Path source, Path target, String role) throws IOException {
        Path safeSource = requireSafeRegularFile(source, role + " source");
        FileSnapshot sourceBefore = snapshot(safeSource, role + " source preflight");

        Path absolute = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException(role + " parent is not an existing directory: " + parent);
        }
        // Explicitly chosen directories may contain a symlink/junction in their original path.
        // Resolve it before creating our private staging space; never follow a link in a
        // catalogue-controlled path or through the final filename.
        Path realParent = requireUserSelectedDirectory(parent, role + " destination parent");
        Path finalTarget = realParent.resolve(absolute.getFileName());
        if (Files.exists(finalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(finalTarget.toString());
        }
        Path workspace = createPrivateWorkspace(realParent, ".mhl-conversion-publish-", role + " workspace");
        try {
            Path pending = workspace.resolve("pending");
            try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS)) {
                copyFresh(in, pending, role + " staged publication");
            }
            FileSnapshot sourceAfter = snapshot(safeSource, role + " source verification");
            FileSnapshot pendingSnapshot = snapshot(pending, role + " staged verification");
            if (!sourceBefore.equals(sourceAfter) || !sourceBefore.equals(pendingSnapshot)) {
                throw new IOException(role + " staged source changed during publication");
            }
            // Prefer atomic hard-link publication. On volumes that reject hard links
            // with ERROR_INVALID_FUNCTION, use a fresh non-overwriting copy instead.
            // That fallback can briefly expose incomplete *new* output, so the
            // resulting file is verified before any catalog mutation or success.
            SafeFilePrimitives.publishFreshNoReplace(pending, finalTarget, role);
            FileSnapshot published = snapshotUserSelectedFile(finalTarget, role + " published file");
            if (!sourceBefore.equals(published)) {
                // A foreign replacement must never be removed by rollback.
                removeUserSelectedFileIfOwned(finalTarget, pendingSnapshot.sha256(),
                        role + " failed-publication rollback");
                throw new IOException(role + " published bytes differ from staged source");
            }
            return published;
        } finally {
            // Successful publication must not be reported as failed solely because a
            // disposable hidden staging directory could not be removed (Windows locks).
            try { cleanupPrivateWorkspace(workspace); }
            catch (IOException cleanup) {
                System.getLogger(SafeFilePublication.class.getName()).log(System.Logger.Level.WARNING,
                        "PUBLICATION_CLEANUP_FAILED temporary={0} error={1}", workspace.getFileName(), cleanup.getClass().getSimpleName());
            }
        }
    }

    /** Snapshot of a fresh leaf under an explicitly user-selected directory; parent links are allowed. */
    public static FileSnapshot snapshotUserSelectedFile(Path file, String role) throws IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException(role + " is a symbolic link: " + absolute);
        }
        BasicFileAttributes attrs = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isOther()) {
            throw new IOException(role + " is not a regular file: " + absolute);
        }
        try (FileChannel channel = FileChannel.open(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new FileSnapshot(sha256(channel, role), channel.size());
        }
    }

    /** Best-effort rollback for a file created under an explicitly user-selected directory. */
    public static boolean removeUserSelectedFileIfOwned(Path file, String expectedHash, String role) throws IOException {
        if (file == null || expectedHash == null || expectedHash.isBlank()
                || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        FileSnapshot current = snapshotUserSelectedFile(file, role + " ownership check");
        if (!expectedHash.equalsIgnoreCase(current.sha256())) return false;
        return Files.deleteIfExists(file);
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

    public static Path copyFresh(InputStream source, Path target, String role) throws IOException {
        Objects.requireNonNull(source, "source");
        try (OutputStream out = openFreshOutputStream(target, role)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = source.read(buffer)) >= 0; ) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        }
        return requireSafeRegularFile(target, role);
    }

    /**
     * Opens a newly-created regular file for read/write without following links. The returned
     * channel is useful for long-running writers that must later prove that the pathname still
     * refers to the exact bytes written through this handle.
     */
    public static FileChannel openFreshReadWriteChannel(Path target, String role) throws IOException {
        Path absolute = prepareTarget(target, role);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        try {
            return FileChannel.open(absolute, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException race) {
            throw new IOException(role + " appeared while opening a fresh file: " + absolute, race);
        }
    }

    /**
     * Opens an existing file for append only when the bytes visible through the opened handle
     * still match the caller's ownership snapshot. A regular-file replacement between the path
     * check and the open is therefore detected before any bytes are written.
     */
    public static FileChannel openOwnedAppendChannel(
            Path target, String expectedHash, String role) throws IOException {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash is required");
        }
        Path safe = requireSafeRegularFile(target, role);
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        boolean ok = false;
        try {
            String openedHash = sha256(channel, role + " opened file");
            if (!expectedHash.equalsIgnoreCase(openedHash)) {
                throw new IOException(role + " changed before append: " + safe);
            }
            channel.position(channel.size());
            ok = true;
            return channel;
        } finally {
            if (!ok) channel.close();
        }
    }

    /**
     * Captures hash and size from the same no-follow handle. This avoids a check/hash -> Files.size
     * pathname race where metadata could describe a replacement file rather than the bytes that
     * were actually verified.
     */
    public static FileSnapshot snapshot(Path file, String role) throws IOException {
        Path safe = requireSafeRegularFile(file, role);
        try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new FileSnapshot(sha256(channel, role), channel.size());
        }
    }

    /** SHA-256 of the bytes referenced by an already-open channel; preserves its position. */
    public static String sha256(FileChannel channel, String role) throws IOException {
        Objects.requireNonNull(channel, "channel");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        long position = channel.position();
        try {
            channel.position(0L);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) >= 0) {
                if (buffer.position() == 0) continue;
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            channel.position(Math.min(position, channel.size()));
        }
    }

    /** Verifies that the pathname still resolves to the caller-owned bytes. */
    public static void requireOwnedPath(Path target, String expectedHash, String role) throws IOException {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash is required");
        }
        String current = sha256(target, role);
        if (!expectedHash.equalsIgnoreCase(current)) {
            throw new IOException(role + " changed while the operation was in progress: " + target);
        }
    }

    /**
     * Reads a bounded file through one no-follow handle and proves that the pathname still names
     * the same bytes afterwards. This prevents a regular-file replacement between validation and
     * parsing from silently changing the input object.
     */
    public static byte[] readOwnedBytes(Path target, String expectedHash, int maxBytes, String role) throws IOException {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash is required");
        }
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        Path safe = requireSafeRegularFile(target, role);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            while (channel.read(buffer) >= 0) {
                int count = buffer.position();
                if (count == 0) continue;
                if (total > maxBytes - count) {
                    throw new IOException(role + " exceeds safety limit of " + maxBytes + " bytes");
                }
                buffer.flip();
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                digest.update(chunk);
                out.write(chunk);
                total += count;
                buffer.clear();
            }
            String openedHash = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equalsIgnoreCase(openedHash)) {
                throw new IOException(role + " changed before read: " + safe);
            }
            bytes = out.toByteArray();
        }
        requireOwnedPath(safe, expectedHash, role + " pathname verification");
        return bytes;
    }

    public static Path moveNoReplace(Path source, Path target, String role) throws IOException {
        return SafeFilePrimitives.moveNoReplace(
                source, target, role, SafeFilePublication::requireSafeRegularFile, SafeFilePublication::prepareTarget);
    }

    /**
     * Publishes a staged file while protecting the destination against concurrent replacement.
     * A null expectedTargetHash means the destination was absent when the operation started and
     * therefore must still be absent at commit. A non-null hash authorizes replacement only if
     * the destination still contains exactly those bytes.
     */
    public static void publishReplacingIfUnchanged(
            Path staging, Path target, String expectedTargetHash, String role) throws IOException {
        FileSnapshot staged = snapshot(staging, role + " staged preflight");
        publishReplacingIfUnchanged(staging, target, expectedTargetHash, staged.sha256(), role);
    }

    /**
     * Publishes only the exact staging bytes previously validated by the caller. The expected
     * staging hash is checked before destination mutation and again after the move, closing the
     * validate/check -> replace staging pathname race.
     */
    public static void publishReplacingIfUnchanged(
            Path staging, Path target, String expectedTargetHash, String expectedStagingHash, String role) throws IOException {
        if (expectedStagingHash == null || expectedStagingHash.isBlank()) {
            throw new IllegalArgumentException("expectedStagingHash is required");
        }
        FileSnapshot stagedSnapshot = snapshot(staging, role + " staged preflight");
        if (!expectedStagingHash.equalsIgnoreCase(stagedSnapshot.sha256())) {
            throw new IOException(role + " staged input changed before publication: " + staging);
        }

        Path detached = null;
        boolean published = false;
        IOException failure = null;
        try {
            if (expectedTargetHash != null) {
                detached = detachIfOwned(target, expectedTargetHash, role + " existing target");
            } else {
                Path safeTarget = prepareTarget(target, role + " target");
                if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(safeTarget.toString());
                }
            }

            moveNoReplace(staging, target, role + " publication");
            String publishedHash = sha256(target, role + " published target");
            if (!expectedStagingHash.equalsIgnoreCase(publishedHash)) {
                IOException mismatch = new IOException(role
                        + " published bytes differ from the validated staged bytes: " + target);
                try {
                    // The moved pathname is not the validated object. Preserve those unexpected
                    // bytes as evidence before restoring the prior destination when possible.
                    detachIfOwned(target, publishedHash, role + " unexpected publication");
                } catch (IOException quarantineFailure) {
                    mismatch.addSuppressed(quarantineFailure);
                }
                if (detached != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        moveNoReplace(detached, target, role + " rollback");
                        requireOwnedPath(target, expectedTargetHash, role + " rollback verification");
                        detached = null;
                    } catch (IOException restoreFailure) {
                        mismatch.addSuppressed(restoreFailure);
                    }
                }
                throw mismatch;
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
                    if (expectedTargetHash != null) {
                        requireOwnedPath(target, expectedTargetHash, role + " rollback verification");
                    }
                    detached = null;
                } catch (IOException restoreFailure) {
                    if (failure != null) failure.addSuppressed(restoreFailure);
                }
            }
            if (published && detached != null) {
                try {
                    removeIfOwned(detached, expectedTargetHash, role + " old target cleanup");
                } catch (IOException cleanupFailure) {
                    // Publication is already verified; preserve unverified evidence rather than deleting it.
                }
            }
        }
    }

    /** Returns the current target hash, or null if the target does not exist. */
    public static String snapshotExistingTarget(Path target, String role) throws IOException {
        Path safe = prepareTarget(target, role);
        if (!Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) return null;
        return sha256(safe, role + " snapshot");
    }

    public static String sha256(Path file, String role) throws IOException {
        return SafeFilePrimitives.sha256(file, role, SafeFilePublication::requireSafeRegularFile);
    }

    /**
     * Detaches an existing target only if it still contains the bytes represented by expectedHash.
     * If the target changes during detach, the changed bytes are restored and the operation fails.
     */
    public static Path detachIfOwned(Path target, String expectedHash, String role) throws IOException {
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("expectedHash is required");
        }
        Path safeTarget = requireSafeRegularFile(target, role + " target");
        String before = sha256(safeTarget, role + " target snapshot");
        if (!expectedHash.equalsIgnoreCase(before)) {
            throw new IOException(role + " target changed before detach: " + safeTarget);
        }
        Path parent = safeTarget.getParent();
        Path evidence = parent.resolve("." + safeTarget.getFileName() + ".mhl-evidence-" + UUID.randomUUID() + ".tmp");
        Path detached = moveNoReplace(safeTarget, evidence, role + " detach");
        String after = sha256(detached, role + " detached target");
        if (!expectedHash.equalsIgnoreCase(after)) {
            try {
                if (!Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    moveNoReplace(detached, safeTarget, role + " changed-target restore");
                }
            } catch (Exception restoreFailure) {
                IOException failure = new IOException(role + " target changed during detach; evidence preserved at " + detached);
                failure.addSuppressed(restoreFailure);
                throw failure;
            }
            throw new IOException(role + " target changed during detach");
        }
        return detached;
    }

    /** Removes a path only when its current bytes are still owned by the caller. */
    public static boolean removeIfOwned(Path target, String expectedHash, String role) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
        Path detached = detachIfOwned(target, expectedHash, role);
        Files.delete(detached);
        return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Deletes an application-managed disposable file without ever accepting a link, directory or
     * special/reparse entry in its place. This is intended for files such as SQLite sidecars and
     * generation markers whose bytes do not need an ownership hash, but whose pathname must still
     * obey the same fail-closed boundary as the primary artifact.
     */
    public static boolean deleteSafeRegularFileIfExists(Path target, String role) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException(role + " has no parent directory: " + absolute);
        requireSafeDirectoryPath(parent, role + " parent");
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException(role + " is a symbolic link: " + absolute);
        }
        BasicFileAttributes attrs = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isOther()) {
            throw new IOException(role + " is not a safe regular file: " + absolute);
        }
        Files.delete(absolute);
        return !Files.exists(absolute, LinkOption.NOFOLLOW_LINKS);
    }

    public static void cleanupPrivateWorkspace(Path workspace) throws IOException {
        NoLinkPathSafety.cleanupPrivateWorkspace(workspace, "Application private workspace cleanup");
    }

    public static Path requireSafeDirectoryPath(Path directory, String role) throws IOException {
        return NoLinkPathSafety.requireDirectory(directory, role);
    }
}
