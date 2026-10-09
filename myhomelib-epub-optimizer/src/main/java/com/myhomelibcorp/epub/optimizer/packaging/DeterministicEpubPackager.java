package com.myhomelibcorp.epub.optimizer.packaging;

import com.myhomelibcorp.epub.optimizer.archive.EpubZipWriterSupport;
import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

@Component
public class DeterministicEpubPackager implements EpubPackager {
    private static final LocalDateTime DETERMINISTIC_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    @Override public ZipCompressionBackend backend() { return ZipCompressionBackend.JAVA_DEFLATE_9; }

    @Override
    public void repack(Path source, Path target, EpubPackagingProfile profile) throws Exception {
        Path normalizedSource = EpubPathSafety.requireSafeRegularFile(source, "EPUB packaging source");
        Path normalizedTarget = EpubPathSafety.prepareTarget(target, "EPUB packaging target");
        checkCancelled();
        try (ZipFile zip = new ZipFile(normalizedSource.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(EpubPathSafety.openFreshOutputStream(normalizedTarget, "EPUB package target"), StandardCharsets.UTF_8)) {
            out.setLevel(profile.deflateLevel());
            EpubZipWriterSupport.writeMimetype(out, profile.deterministicTimestamps() ? DETERMINISTIC_TIME : null);
            List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> !"mimetype".equals(e.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry e) -> rank(e.getName())).thenComparing(ZipEntry::getName))
                    .toList();
            if (entries.size() + 1L > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                throw new IllegalArgumentException("EPUB contains too many ZIP entries");
            Set<String> seen = new HashSet<>();
            seen.add("mimetype");
            byte[] buffer = new byte[64 * 1024];
            long totalWritten = EpubZipWriterSupport.mimetypeLength();
            for (ZipEntry entry : entries) {
                checkCancelled();
                if (!seen.add(entry.getName())) throw new IllegalArgumentException("Duplicate EPUB ZIP entry: " + entry.getName());
                if (unsafe(entry.getName())) throw new IllegalArgumentException("Unsafe EPUB ZIP entry: " + entry.getName());
                validateDeclaredEntry(entry);
                ZipEntry copy = new ZipEntry(entry.getName());
                if (profile.deterministicTimestamps()) copy.setTimeLocal(DETERMINISTIC_TIME);
                else if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                copy.setExtra(null);
                CompressionChoice choice = compressionFor(zip, entry, profile, buffer);
                if (choice.decision() == ZipEntryCompressionDecision.STORE) {
                    EntryStats stats = choice.stats() != null ? choice.stats() : stats(zip, entry, buffer);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(stats.size());
                    copy.setCompressedSize(stats.size());
                    copy.setCrc(stats.crc());
                } else {
                    copy.setMethod(ZipEntry.DEFLATED);
                }
                out.putNextEntry(copy);
                long entryWritten = 0L;
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        checkCancelled();
                        if (n <= 0) continue;
                        entryWritten = checkedAdd(entryWritten, n, ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                                "EPUB entry exceeds decompressed size limit: " + entry.getName());
                        totalWritten = checkedAdd(totalWritten, n, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES,
                                "EPUB exceeds cumulative decompressed size limit");
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        }
    }

    private EntryStats stats(ZipFile zip, ZipEntry entry, byte[] buffer) throws Exception {
        CRC32 crc = new CRC32(); long size = 0;
        validateDeclaredEntry(entry);
        try (InputStream in = zip.getInputStream(entry)) {
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkCancelled();
                if (n > 0) {
                    size = checkedAdd(size, n, ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                            "EPUB entry exceeds decompressed size limit: " + entry.getName());
                    crc.update(buffer, 0, n);
                }
            }
        }
        return new EntryStats(size, crc.getValue(), -1L);
    }

    private int rank(String name) { return "META-INF/container.xml".equals(name) ? 0 : 1; }

    private CompressionChoice compressionFor(ZipFile zip, ZipEntry entry, EpubPackagingProfile profile, byte[] buffer) throws Exception {
        String lower = entry.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".avif") || lower.endsWith(".jxl") || lower.endsWith(".woff2")
                || lower.endsWith(".mp3") || lower.endsWith(".mp4")) {
            return new CompressionChoice(ZipEntryCompressionDecision.STORE, null);
        }
        if (lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".woff")
                || lower.endsWith(".ttf") || lower.endsWith(".otf")) {
            EntryStats stats = statsWithDeflate(zip, entry, buffer, profile.deflateLevel());
            // Avoid Deflate when the payload saving is negligible; both methods are OCF-compatible.
            if (stats.deflatedSize() >= Math.max(0L, stats.size() - 16L)) {
                return new CompressionChoice(ZipEntryCompressionDecision.STORE, stats);
            }
            return new CompressionChoice(ZipEntryCompressionDecision.DEFLATE, stats);
        }
        return new CompressionChoice(ZipEntryCompressionDecision.DEFLATE, null);
    }

    private EntryStats statsWithDeflate(ZipFile zip, ZipEntry entry, byte[] buffer, int level) throws Exception {
        CRC32 crc = new CRC32();
        long size = 0L;
        CountingOutputStream counter = new CountingOutputStream();
        Deflater deflater = new Deflater(level, true);
        validateDeclaredEntry(entry);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(counter, deflater, 64 * 1024, true);
             InputStream in = zip.getInputStream(entry)) {
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkCancelled();
                if (n > 0) {
                    size = checkedAdd(size, n, ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                            "EPUB entry exceeds decompressed size limit: " + entry.getName());
                    crc.update(buffer, 0, n);
                    compressed.write(buffer, 0, n);
                }
            }
            compressed.finish();
        } finally {
            deflater.end();
        }
        return new EntryStats(size, crc.getValue(), counter.count());
    }

    private void validateDeclaredEntry(ZipEntry entry) {
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize()))
            throw new IllegalArgumentException("EPUB entry exceeds declared size limit: " + entry.getName());
        if (ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize()))
            throw new IllegalArgumentException("EPUB entry exceeds compression-ratio limit: " + entry.getName());
        int method = entry.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
            throw new IllegalArgumentException("Unsupported EPUB ZIP method for entry: " + entry.getName());
    }

    private long checkedAdd(long current, long delta, long limit, String message) {
        if (delta < 0L || current > limit - delta) throw new IllegalArgumentException(message);
        return current + delta;
    }

    private boolean unsafe(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\','/').split("/")) if ("..".equals(part)) return true;
        return false;
    }

    private record EntryStats(long size, long crc, long deflatedSize) { }
    private record CompressionChoice(ZipEntryCompressionDecision decision, EntryStats stats) { }
    private static final class CountingOutputStream extends OutputStream {
        private long count;
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
        long count() { return count; }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
