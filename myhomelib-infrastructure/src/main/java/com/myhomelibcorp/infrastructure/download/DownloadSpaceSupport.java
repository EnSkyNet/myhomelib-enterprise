package com.myhomelibcorp.infrastructure.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared overflow-safe disk-space guard for download paths. */
public final class DownloadSpaceSupport {
    private DownloadSpaceSupport() {
    }

    @FunctionalInterface
    public interface FailureFactory<E extends IOException> {
        E create(long usableBytes, long requiredBytes);
    }

    /**
     * Verifies that the target file store can accommodate the configured reserve plus the
     * expected remaining payload. Integer overflow is treated conservatively as an impossible
     * {@link Long#MAX_VALUE} requirement.
     */
    public static <E extends IOException> void requireAvailable(
            Path directory,
            long minimumFreeBytes,
            long expectedAdditionalBytes,
            FailureFactory<E> failureFactory) throws IOException {
        if (directory == null || minimumFreeBytes <= 0 && expectedAdditionalBytes <= 0) return;
        Objects.requireNonNull(failureFactory, "failureFactory");

        long usable = Files.getFileStore(directory).getUsableSpace();
        long required = requiredBytes(minimumFreeBytes, expectedAdditionalBytes);
        if (usable < required) throw failureFactory.create(usable, required);
    }

    static long requiredBytes(long minimumFreeBytes, long expectedAdditionalBytes) {
        try {
            return Math.addExact(minimumFreeBytes, Math.max(0L, expectedAdditionalBytes));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
