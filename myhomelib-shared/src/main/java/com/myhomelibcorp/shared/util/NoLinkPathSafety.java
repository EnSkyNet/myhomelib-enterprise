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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

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
            // A concurrent publisher won CREATE_NEW after the preflight check.
            // Preserve the collision type: callers must be able to distinguish
            // an occupied destination from I/O failures, without any overwrite.
            throw race;
        }
    }

    public static Path copyFreshFile(Path source, Path target, String role) throws IOException {
        return copyFreshFile(source, target, role, () -> false);
    }

    /**
     * Cooperative cancellation of long-running, no-replace file copies.
     * The caller can cancel between bounded I/O chunks without publishing an
     * incomplete output. This cannot forcibly interrupt a blocked OS read/write.
     */
    public static Path copyFreshFile(Path source, Path target, String role,
                                     BooleanSupplier cancelled) throws IOException {
        return copyFreshFileInternal(source, target, role, cancelled, null, (stage, bytes) -> { });
    }

    /**
     * Copies into a CREATE_NEW destination while hashing the same source bytes being
     * copied. A stale expected digest is rejected before returning the result; the
     * failure handler removes only the output owned by this invocation (when the
     * filesystem exposes a reliable file key). This avoids a second, potentially
     * expensive and racy read of the source immediately before the actual copy.
     * Callers should independently verify destination bytes before publication.
     */
    public static Path copyFreshFileVerified(Path source, Path target, String role,
                                             BooleanSupplier cancelled, String expectedSha256) throws IOException {
        return copyFreshFileVerified(source, target, role, cancelled, expectedSha256, (stage, bytes) -> { });
    }

    /**
     * The listener sees bounded milestones: OPENED, BYTES, STREAM_DONE,
     * VERIFY_TARGET_START and VERIFY_TARGET_DONE. It must be quick and must not
     * access or mutate the copied file. The verified destination SHA and the final
     * cancellation checkpoint run inside the same ownership-aware cleanup scope.
     */
    public static Path copyFreshFileVerified(Path source, Path target, String role,
                                             BooleanSupplier cancelled, String expectedSha256,
                                             BiConsumer<String, Long> milestone) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("A validated SHA-256 is required for verified copy");
        }
        return copyFreshFileInternal(source, target, role, cancelled, expectedSha256, milestone);
    }

    private static Path copyFreshFileInternal(Path source, Path target, String role,
                                              BooleanSupplier cancelled, String expectedSha256,
                                              BiConsumer<String, Long> milestone) throws IOException {
        Objects.requireNonNull(milestone, "milestone");
        Objects.requireNonNull(cancelled, "cancelled");
        checkCopyCancelled(cancelled);
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
        MessageDigest copiedDigest = null;
        if (expectedSha256 != null) {
            try {
                copiedDigest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable", unavailable);
            }
        }
        try {
            long bytesCopied = 0L;
            try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
                 OutputStream out = openFreshOutputStream(safeTarget, role + " target")) {
            targetOpened = true;
            createdFileKey = Files.readAttributes(safeTarget, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            milestone.accept("OPENED", 0L);
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long start = System.nanoTime();
            long lastProgress = start;
            while (true) {
                checkCopyCancelled(cancelled);
                int read = in.read(buffer);
                if (read < 0) break;
                checkCopyCancelled(cancelled);
                if (read > 0) {
                    out.write(buffer, 0, read);
                    if (copiedDigest != null) copiedDigest.update(buffer, 0, read);
                    bytesCopied += read;
                    milestone.accept("BYTES", bytesCopied);
                }
                long now = System.nanoTime();
                if (now - lastProgress >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                    System.getLogger(NoLinkPathSafety.class.getName()).log(System.Logger.Level.INFO,
                            "FILE_COPY_PROGRESS role={0} copiedBytes={1} elapsedMs={2}",
                            role, bytesCopied,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - start));
                    lastProgress = now;
                }
            }
            checkCopyCancelled(cancelled);
            out.flush();
            if (copiedDigest != null && !expectedSha256.equalsIgnoreCase(
                    HexFormat.of().formatHex(copiedDigest.digest()))) {
                throw new IllegalStateException("Source hash mismatch before copy or during copy: " + safeSource);
            }
            }
            milestone.accept("STREAM_DONE", bytesCopied);
            if (expectedSha256 != null) {
                // This second pass verifies bytes actually stored on the target, not
                // only bytes passed to OutputStream.write. Keep verification AND the
                // final cancellation check within the ownership-aware catch below.
                milestone.accept("VERIFY_TARGET_START", bytesCopied);
                MessageDigest targetDigest;
                try {
                    targetDigest = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException unavailable) {
                    throw new IllegalStateException("SHA-256 unavailable", unavailable);
                }
                byte[] verifyBuffer = new byte[COPY_BUFFER_BYTES];
                try (InputStream targetIn = Files.newInputStream(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                    int read;
                    while (true) {
                        checkCopyCancelled(cancelled);
                        read = targetIn.read(verifyBuffer);
                        if (read < 0) break;
                        if (read > 0) targetDigest.update(verifyBuffer, 0, read);
                    }
                }
                if (!expectedSha256.equalsIgnoreCase(HexFormat.of().formatHex(targetDigest.digest()))) {
                    throw new IllegalStateException("Staged hash mismatch: " + safeTarget);
                }
                milestone.accept("VERIFY_TARGET_DONE", bytesCopied);
            }
            checkCopyCancelled(cancelled);
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

    private static void checkCopyCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("Managed file copy cancelled");
        }
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
