package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Replaces existing binary resources in-place by ZIP path; resource names and manifest references do not change. */
@Component
public final class EpubResourceReplacementWriter {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    public int replace(Path source, Path target, Map<String, Path> replacements) throws Exception {
        Map<String, Path> safe = replacements == null ? Map.of() : Map.copyOf(replacements);
        Path normalizedSource = EpubPathSafety.requireSafeRegularFile(source, "EPUB replacement source");
        Path normalizedTarget = EpubPathSafety.prepareTarget(target, "EPUB replacement target");
        if (safe.isEmpty()) {
            EpubPathSafety.copyFreshFile(normalizedSource, normalizedTarget, "EPUB replacement passthrough");
            return 0;
        }
        try (ZipFile zip = new ZipFile(normalizedSource.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(EpubPathSafety.openFreshOutputStream(normalizedTarget, "EPUB replacement target"), StandardCharsets.UTF_8)) {
            out.setLevel(9);
            writeMimetype(out);
            var entries = Collections.list(zip.entries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !"mimetype".equals(entry.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry entry) -> "META-INF/container.xml".equals(entry.getName()) ? 0 : 1)
                            .thenComparing(ZipEntry::getName))
                    .toList();
            if (entries.size() + 1L > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                throw new IllegalArgumentException("EPUB contains too many ZIP entries");
            Set<String> seen = new HashSet<>();
            seen.add("mimetype");
            byte[] buffer = new byte[64 * 1024];
            long totalBytes = MIMETYPE.length;
            int usedReplacements = 0;
            for (ZipEntry entry : entries) {
                checkCancelled();
                validateEntry(entry, seen);
                Path replacement = safe.get(entry.getName());
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(ZIP_TIME);
                copy.setExtra(null);
                copy.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(copy);
                if (replacement != null) {
                    usedReplacements++;
                    Path safeReplacement = EpubPathSafety.requireSafeRegularFile(replacement, "EPUB replacement resource");
                    long replacementSize = Files.size(safeReplacement);
                    if (replacementSize > ArchiveSafetyLimits.MAX_ENTRY_BYTES)
                        throw new IllegalArgumentException("Replacement resource exceeds size limit: " + entry.getName());
                    try (InputStream in = Files.newInputStream(safeReplacement, LinkOption.NOFOLLOW_LINKS)) {
                        totalBytes = copy(in, out, buffer, totalBytes, entry.getName());
                    }
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        totalBytes = copy(in, out, buffer, totalBytes, entry.getName());
                    }
                }
                out.closeEntry();
            }
            if (usedReplacements != safe.size()) {
                throw new IllegalArgumentException("Replacement map contains resources that are not present in the EPUB");
            }
        }
        return safe.size();
    }

    private long copy(InputStream in, ZipOutputStream out, byte[] buffer, long totalBytes, String name) throws Exception {
        long entryBytes = 0L;
        for (int read; (read = in.read(buffer)) >= 0;) {
            checkCancelled();
            if (read <= 0) continue;
            if (entryBytes > ArchiveSafetyLimits.MAX_ENTRY_BYTES - read)
                throw new IllegalArgumentException("EPUB entry exceeds decompressed size limit: " + name);
            if (totalBytes > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES - read)
                throw new IllegalArgumentException("EPUB exceeds cumulative decompressed size limit");
            entryBytes += read;
            totalBytes += read;
            out.write(buffer, 0, read);
        }
        return totalBytes;
    }

    private void validateEntry(ZipEntry entry, Set<String> seen) {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("Unsafe EPUB ZIP entry");
        for (String part : name.replace('\\', '/').split("/")) {
            if ("..".equals(part)) throw new IllegalArgumentException("Unsafe EPUB ZIP entry: " + name);
        }
        if (!seen.add(name)) throw new IllegalArgumentException("Duplicate EPUB ZIP entry: " + name);
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize()))
            throw new IllegalArgumentException("EPUB entry exceeds declared size limit: " + name);
        if (ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize()))
            throw new IllegalArgumentException("EPUB entry exceeds compression-ratio limit: " + name);
        if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
            throw new IllegalArgumentException("Unsupported EPUB ZIP method: " + name);
    }

    private void writeMimetype(ZipOutputStream out) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(MIMETYPE);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(MIMETYPE.length);
        entry.setCompressedSize(MIMETYPE.length);
        entry.setCrc(crc.getValue());
        entry.setTime(ZIP_TIME.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        entry.setExtra(null);
        out.putNextEntry(entry);
        out.write(MIMETYPE);
        out.closeEntry();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("EPUB resource replacement cancelled");
    }
}
