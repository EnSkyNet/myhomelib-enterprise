package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Finalizes a committed export before the application reports it as complete.
 *
 * <p>{@link ExportRequest.CompletionPolicy#EJECT_SAFE} requires a successful
 * file-level force so Java has handed data and file metadata to the filesystem
 * before success is reported. Parent-directory metadata is forced when the
 * host/filesystem exposes directory channels; that extra step is deliberately
 * best-effort because it is not portable across all Windows/device filesystems.</p>
 */
@Component
@Slf4j
public class ExportCompletionService {

    public void complete(Path targetFile, ExportRequest.CompletionPolicy policy) throws IOException {
        ExportRequest.CompletionPolicy effective = policy == null
                ? ExportRequest.CompletionPolicy.VERIFY_READABLE : policy;
        if (effective != ExportRequest.CompletionPolicy.EJECT_SAFE) return;

        Path safe = SafeFilePublication.requireSafeRegularFile(targetFile, "Committed export target");
        try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        forceParentDirectoryBestEffort(safe.getParent());
    }

    /**
     * Finalizes only the exact bytes that were staged for this export. Hash verification and the
     * optional durability flush happen through one no-follow handle before publication is treated
     * as committed; the pathname is verified again afterwards to catch a concurrent replacement.
     */
    public void complete(Path targetFile, ExportRequest.CompletionPolicy policy, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new IllegalArgumentException("expectedSha256 is required");
        }
        ExportRequest.CompletionPolicy effective = policy == null
                ? ExportRequest.CompletionPolicy.VERIFY_READABLE : policy;
        Path safe = SafeFilePublication.requireSafeRegularFile(targetFile, "Committed export target");
        boolean force = effective == ExportRequest.CompletionPolicy.EJECT_SAFE;
        if (force) {
            try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                verifyOwnedChannel(channel, safe, expectedSha256);
                channel.force(true);
            }
        } else {
            try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                verifyOwnedChannel(channel, safe, expectedSha256);
            }
        }
        SafeFilePublication.requireOwnedPath(safe, expectedSha256, "Committed export pathname verification");
        if (force) forceParentDirectoryBestEffort(safe.getParent());
    }

    private static void verifyOwnedChannel(FileChannel channel, Path targetFile, String expectedSha256) throws IOException {
        if (channel.size() <= 0L) throw new IOException("Committed export file is empty: " + targetFile);
        String actual = SafeFilePublication.sha256(channel, "Committed export target");
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            throw new IOException("Committed export target changed before finalization: " + targetFile);
        }
    }

    private void forceParentDirectoryBestEffort(Path directory) {
        if (directory == null) return;
        try {
            Path safeDirectory = SafeFilePublication.requireSafeDirectoryPath(
                    directory, "Export parent directory");
            try (FileChannel channel = FileChannel.open(
                    safeDirectory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        } catch (IOException | RuntimeException unsupported) {
            log.debug("Parent-directory durability flush is not available for {}: {}",
                    directory, unsupported.getMessage());
        }
    }
}
