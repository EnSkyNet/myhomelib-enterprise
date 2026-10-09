package com.myhomelibcorp.infrastructure.importer.archive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
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

        Path temp = Files.createTempFile("mhl-archive-entry-", ArchiveImportSupport.suffixFor(entryName));
        try {
            long entryBytes = copyBounded(reader, temp);
            long updatedTotal = addToCumulativeTotal(totalDecompressedBytes, entryBytes);
            try (Stream<Book> books = importer.importBooks(temp)) {
                books.filter(Objects::nonNull)
                        .map(book -> ArchiveImportSupport.enrich(book, archivePath, entryName, entryBytes))
                        .forEach(destination::add);
            }
            return updatedTotal;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static long copyBounded(EntryReader reader, Path target) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (read == 0) {
                    throw new IOException("Archive entry reader made no progress");
                }
                if (read < 0 || read > buffer.length) {
                    throw new IOException("Archive entry reader returned invalid byte count: " + read);
                }
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES - read) {
                    throw new EntryLimitException("Archive entry exceeds decompression safety limit");
                }
                out.write(buffer, 0, read);
                total += read;
            }
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
