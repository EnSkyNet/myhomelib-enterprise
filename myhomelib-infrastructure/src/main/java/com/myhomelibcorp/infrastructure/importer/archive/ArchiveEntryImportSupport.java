package com.myhomelibcorp.infrastructure.importer.archive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/**
 * Shared bounded materialization/import pipeline for non-random-access archive readers.
 *
 * <p>The caller owns archive iteration and decides whether an entry-level failure is
 * recoverable. This class owns the invariants that must not drift between archive
 * formats: bounded decompression, cumulative limits, temporary-file cleanup and
 * archive-origin enrichment.</p>
 */
public final class ArchiveEntryImportSupport {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private ArchiveEntryImportSupport() { }

    @FunctionalInterface
    public interface EntryReader {
        int read(byte[] buffer) throws IOException;
    }

    public static long materializeAndImport(
            Path archivePath,
            String entryName,
            BookImporterPort importer,
            EntryReader reader,
            long totalDecompressedBytes,
            Queue<Book> destination) throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(importer, "importer");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(destination, "destination");

        // Fail before creating temporary artifacts when the previous members already
        // exhausted the archive budget; account bytes while copying, not afterwards.
        addToCumulativeTotal(totalDecompressedBytes, 0);
        long remainingArchiveBudget = ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - totalDecompressedBytes;
        Path temp = Files.createTempFile("mhl-archive-entry-", ArchiveImportSupport.suffixFor(entryName));
        try {
            long entryBytes = copyBounded(reader, temp, remainingArchiveBudget);
            long updatedTotal = addToCumulativeTotal(totalDecompressedBytes, entryBytes);
            // A streaming parser can yield books and then fail. Never expose the
            // partially parsed entry to the enclosing archive transaction/queue.
            Queue<Book> parsedEntry = new ArrayDeque<>();
            try (Stream<Book> books = importer.importBooks(temp)) {
                books.filter(Objects::nonNull)
                        .map(book -> ArchiveImportSupport.enrich(book, archivePath, entryName, entryBytes))
                        .forEach(parsedEntry::add);
            }
            destination.addAll(parsedEntry);
            return updatedTotal;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Fully consumes an entry that must be skipped, while charging its actual
     * decompressed bytes to the same per-entry and archive-wide limits as imports.
     * This is required by sequential/solid readers whose next-entry operation may
     * otherwise decompress the unread remainder internally.
     *
     * @return updated archive-wide decompressed-byte total
     */
    public static long discardEntry(EntryReader reader, long totalDecompressedBytes) throws IOException {
        Objects.requireNonNull(reader, "reader");
        addToCumulativeTotal(totalDecompressedBytes, 0);
        long remainingArchiveBudget = ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - totalDecompressedBytes;
        long entryBytes = consumeBounded(reader, OutputStream.nullOutputStream(), remainingArchiveBudget,
                ArchiveSafetyLimits.MAX_ENTRY_BYTES);
        return addToCumulativeTotal(totalDecompressedBytes, entryBytes);
    }

    /** Copies one random-access archive member under per-entry and cumulative limits while writing. */
    public static long copyEntryBounded(EntryReader reader, Path target, long totalDecompressedBytes) throws IOException {
        return copyEntryBounded(reader, target, totalDecompressedBytes,
                ArchiveSafetyLimits.MAX_ENTRY_BYTES, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
    }

    /** Package-private configurable limits are used only for small deterministic boundary tests. */
    static long copyEntryBounded(EntryReader reader, Path target, long totalDecompressedBytes,
                                 long maxEntryBytes, long maxTotalBytes) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(target, "target");
        if (maxEntryBytes <= 0 || maxTotalBytes <= 0 || totalDecompressedBytes < 0
                || totalDecompressedBytes > maxTotalBytes) {
            throw new CumulativeArchiveLimitException("Invalid archive decompression budget");
        }
        long remainingArchiveBudget = maxTotalBytes - totalDecompressedBytes;
        try (OutputStream out = Files.newOutputStream(target)) {
            long entryBytes = consumeBounded(reader, out, remainingArchiveBudget, maxEntryBytes);
            if (entryBytes > maxTotalBytes - totalDecompressedBytes) {
                throw new CumulativeArchiveLimitException("Archive exceeds cumulative decompression safety limit");
            }
            return totalDecompressedBytes + entryBytes;
        }
    }

    static long copyBounded(EntryReader reader, Path target) throws IOException {
        return copyBounded(reader, target, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
    }

    static long copyBounded(EntryReader reader, Path target, long archiveBudget) throws IOException {
        if (archiveBudget < 0 || archiveBudget > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
            throw new CumulativeArchiveLimitException("Invalid remaining archive decompression budget");
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            return consumeBounded(reader, out, archiveBudget, ArchiveSafetyLimits.MAX_ENTRY_BYTES);
        }
    }

    private static long consumeBounded(EntryReader reader, OutputStream out, long archiveBudget,
                                       long maxEntryBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Archive entry import interrupted");
            }
            int read = reader.read(buffer);
            if (read == -1) break;
            if (read == 0) {
                throw new IOException("Archive entry reader made no progress");
            }
            if (read < 0 || read > buffer.length) {
                throw new IOException("Archive entry reader returned invalid byte count: " + read);
            }
            if (total > maxEntryBytes - read) {
                throw new EntryLimitException("Archive entry exceeds decompression safety limit");
            }
            if (total > archiveBudget - read) {
                throw new CumulativeArchiveLimitException("Archive exceeds cumulative decompression safety limit");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Archive entry import interrupted");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    static long addToCumulativeTotal(long current, long entryBytes) throws CumulativeArchiveLimitException {
        if (current < 0 || entryBytes < 0
                || current > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - entryBytes) {
            throw new CumulativeArchiveLimitException("Archive exceeds cumulative decompression safety limit");
        }
        return current + entryBytes;
    }

    public static class EntryLimitException extends IOException {
        public EntryLimitException(String message) {
            super(message);
        }
    }

    public static final class CumulativeArchiveLimitException extends IOException {
        public CumulativeArchiveLimitException(String message) {
            super(message);
        }
    }
}
