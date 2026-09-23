package com.myhomelibcorp.application.imports.context;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.Sha256Support;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Shared watcher-source validation used at queue and persistence boundaries. */
public final class ImportSourceFingerprintVerifier {
    private static final int BUFFER_BYTES = 256 * 1024;

    private ImportSourceFingerprintVerifier() { }

    public static void verifyExpected(ImportContext context) {
        if (context == null) return;
        String expected = context.getExpectedSourceSha256();
        if (expected == null || expected.isBlank()) return;
        Path file = context.getFile() == null ? null : context.getFile().toAbsolutePath().normalize();
        if (file == null) throw new StaleImportSourceException("Import source is unavailable");
        Path root = context.getRootDirectory() == null ? null : context.getRootDirectory().toAbsolutePath().normalize();
        verifyExpected(file, root, expected);
    }

    public static void verifyExpected(Path file, Path root, String expectedSha256) {
        if (file == null) throw new StaleImportSourceException("Import source is unavailable");
        if (expectedSha256 == null || expectedSha256.isBlank()) return;
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path normalizedRoot = root == null ? null : root.toAbsolutePath().normalize();
        try {
            if (normalizedRoot != null) {
                SafeFilePublication.requireSafeDirectoryPath(normalizedRoot, "Incoming folder");
                if (normalizedFile.getParent() == null || !normalizedFile.getParent().equals(normalizedRoot)) {
                    throw new StaleImportSourceException("Incoming file moved outside the configured folder");
                }
            }
            Path safeFile = SafeFilePublication.requireSafeRegularFile(normalizedFile, "Incoming file");

            var digest = Sha256Support.newDigest();
            try (FileChannel channel = FileChannel.open(
                    safeFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
                while (channel.read(buffer) >= 0) {
                    if (buffer.position() == 0) continue;
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
            }
            String actual = Sha256Support.finish(digest);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                throw new StaleImportSourceException("Incoming file changed after watcher validation");
            }
            SafeFilePublication.requireSafeRegularFile(normalizedFile, "Incoming file after fingerprint verification");
        } catch (StaleImportSourceException stale) {
            throw stale;
        } catch (Exception unsafeSource) {
            throw new StaleImportSourceException("Cannot verify incoming file before import", unsafeSource);
        }
    }
}
