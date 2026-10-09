package com.myhomelibcorp.epub.optimizer.archive;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Single deterministic and bounded ZIP rewrite engine for EPUB optimization passes.
 * Callers own only per-entry transformation decisions; OCF ordering, archive validation,
 * copy bounds and cancellation semantics stay identical across all rewrite paths.
 */
public final class EpubZipTransformSupport {
    private static final LocalDateTime DETERMINISTIC_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private EpubZipTransformSupport() { }

    @FunctionalInterface
    public interface EntryTransformer {
        void write(ZipFile zip, ZipEntry entry, EntryOutput output) throws Exception;
    }

    public static void rewrite(
            Path source,
            Path target,
            String targetDescription,
            Set<String> excludedEntries,
            String cancellationMessage,
            EntryTransformer transformer) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetDescription, "targetDescription");
        Objects.requireNonNull(transformer, "transformer");
        Set<String> excluded = excludedEntries == null ? Set.of() : Set.copyOf(excludedEntries);
        String cancelMessage = cancellationMessage == null || cancellationMessage.isBlank()
                ? "EPUB optimization cancelled" : cancellationMessage;

        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(
                     EpubPathSafety.openFreshOutputStream(target, targetDescription), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> all = Collections.list(zip.entries());
            validateArchive(all);

            out.setLevel(9);
            EpubZipWriterSupport.writeMimetype(out, DETERMINISTIC_TIME);
            Budget budget = new Budget(EpubZipWriterSupport.mimetypeLength());
            List<? extends ZipEntry> entries = all.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !"mimetype".equals(entry.getName()))
                    .filter(entry -> !excluded.contains(entry.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry entry) ->
                                    "META-INF/container.xml".equals(entry.getName()) ? 0 : 1)
                            .thenComparing(ZipEntry::getName))
                    .toList();

            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            for (ZipEntry entry : entries) {
                checkCancelled(cancelMessage);
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(DETERMINISTIC_TIME);
                copy.setExtra(null);
                copy.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(copy);
                EntryOutput output = new EntryOutput(zip, entry, out, buffer, budget, cancelMessage);
                transformer.write(zip, entry, output);
                if (!output.written()) output.writeOriginal();
                out.closeEntry();
            }
        }
    }

    private static void validateArchive(List<? extends ZipEntry> entries) {
        if (entries.size() > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("EPUB contains too many ZIP entries");
        }
        Set<String> seen = new HashSet<>();
        for (ZipEntry entry : entries) {
            String name = entry.getName();
            if (name == null || name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("Unsafe EPUB ZIP entry");
            }
            for (String part : name.replace('\\', '/').split("/")) {
                if ("..".equals(part)) throw new IllegalArgumentException("Unsafe EPUB ZIP entry: " + name);
            }
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate EPUB ZIP entry: " + name);
            if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                throw new IllegalArgumentException("EPUB entry exceeds declared size limit: " + name);
            }
            if (ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize())) {
                throw new IllegalArgumentException("EPUB entry exceeds compression-ratio limit: " + name);
            }
            if (!entry.isDirectory() && entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                throw new IllegalArgumentException("Unsupported EPUB ZIP method: " + name);
            }
        }
    }

    private static void checkCancelled(String message) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException(message);
    }

    public static final class EntryOutput {
        private final ZipFile zip;
        private final ZipEntry entry;
        private final ZipOutputStream out;
        private final byte[] buffer;
        private final Budget budget;
        private final String cancellationMessage;
        private boolean written;

        private EntryOutput(
                ZipFile zip, ZipEntry entry, ZipOutputStream out, byte[] buffer,
                Budget budget, String cancellationMessage) {
            this.zip = zip;
            this.entry = entry;
            this.out = out;
            this.buffer = buffer;
            this.budget = budget;
            this.cancellationMessage = cancellationMessage;
        }

        public void writeOriginal() throws Exception {
            ensureNotWritten();
            try (InputStream in = zip.getInputStream(entry)) {
                copy(in);
            }
            written = true;
        }

        public void writeBytes(byte[] bytes) throws Exception {
            Objects.requireNonNull(bytes, "bytes");
            ensureNotWritten();
            if (bytes.length > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("EPUB entry exceeds decompressed size limit: " + entry.getName());
            }
            budget.add(bytes.length);
            out.write(bytes);
            written = true;
        }

        public void writeStream(InputStream in) throws Exception {
            Objects.requireNonNull(in, "in");
            ensureNotWritten();
            copy(in);
            written = true;
        }

        public String entryName() {
            return entry.getName();
        }

        private boolean written() {
            return written;
        }

        private void ensureNotWritten() {
            if (written) throw new IllegalStateException("EPUB entry content was already written: " + entry.getName());
        }

        private void copy(InputStream in) throws Exception {
            long entryBytes = 0L;
            for (int read; (read = in.read(buffer)) >= 0;) {
                checkCancelled(cancellationMessage);
                if (read <= 0) continue;
                if (entryBytes > ArchiveSafetyLimits.MAX_ENTRY_BYTES - read) {
                    throw new IllegalArgumentException("EPUB entry exceeds decompressed size limit: " + entry.getName());
                }
                entryBytes += read;
                budget.add(read);
                out.write(buffer, 0, read);
            }
        }
    }

    private static final class Budget {
        private long totalBytes;

        private Budget(long initialBytes) {
            this.totalBytes = initialBytes;
        }

        private void add(long bytes) {
            if (bytes < 0 || totalBytes > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - bytes) {
                throw new IllegalArgumentException("EPUB exceeds cumulative decompressed size limit");
            }
            totalBytes += bytes;
        }
    }
}
