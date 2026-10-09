package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.ZipLocalHeaderInspector;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Minimal always-on OCF/archive-safety validation before any EPUB transformation. */
@Component
public class EpubStructuralValidator {
    private static final String MIMETYPE = "application/epub+zip";
    private final EpubResourceGraphAnalyzer graphAnalyzer;

    public EpubStructuralValidator(EpubResourceGraphAnalyzer graphAnalyzer) { this.graphAnalyzer = graphAnalyzer; }

    public void validate(Path epub) throws Exception {
        try {
            epub = EpubPathSafety.requireSafeRegularFile(epub, "EPUB validation source");
        } catch (java.io.IOException unsafe) {
            throw new IllegalArgumentException("EPUB file is missing, empty or unsafe", unsafe);
        }
        if (Files.size(epub) <= 0) throw new IllegalArgumentException("EPUB file is empty");
        validateMimetype(epub);
        validateArchive(epub);
        var graph = graphAnalyzer.analyze(epub);
        graph.warnings().stream()
                .filter(warning -> warning.startsWith("Broken internal EPUB reference:")
                        || warning.startsWith("Broken OPF ")
                        || warning.startsWith("Broken EPUB "))
                .findFirst()
                .ifPresent(warning -> { throw new IllegalArgumentException(warning); });
    }

    private void validateMimetype(Path epub) throws Exception {
        var first = ZipLocalHeaderInspector.inspectFirstEntry(epub, "EPUB validation source");
        if (!"mimetype".equals(first.name())) throw new IllegalArgumentException("EPUB mimetype is not first");
        if (first.method() != ZipEntry.STORED) throw new IllegalArgumentException("EPUB mimetype must be stored");
        if (first.extraLength() > 0) throw new IllegalArgumentException("EPUB mimetype must not have ZIP extra fields");
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry mimetype = zip.getEntry("mimetype");
            if (mimetype == null || mimetype.isDirectory()) throw new IllegalArgumentException("EPUB mimetype entry is missing");
            try (var in = zip.getInputStream(mimetype)) {
                byte[] payload = in.readNBytes(MIMETYPE.length() + 1);
                String value = new String(payload, StandardCharsets.US_ASCII);
                if (payload.length != MIMETYPE.length() || !MIMETYPE.equals(value)) {
                    throw new IllegalArgumentException("EPUB mimetype content is invalid");
                }
            }
        }
    }

    private void validateArchive(Path epub) throws Exception {
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            Set<String> seen = new HashSet<>();
            int count = 0;
            long total = 0L;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("EPUB contains too many ZIP entries");
                }
                String name = entry.getName();
                if (unsafe(name)) throw new IllegalArgumentException("Unsafe EPUB ZIP entry: " + name);
                if (!seen.add(name)) throw new IllegalArgumentException("Duplicate EPUB ZIP entry: " + name);
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                    throw new IllegalArgumentException("Unsupported EPUB ZIP compression method for " + name);
                }
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0L) throw new IllegalArgumentException("EPUB ZIP entry has unknown uncompressed size: " + name);
                if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) {
                    throw new IllegalArgumentException("EPUB ZIP entry exceeds safety limit: " + name);
                }
                if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressed)) {
                    throw new IllegalArgumentException("EPUB ZIP entry compression ratio exceeds safety limit: " + name);
                }
                if (size > Long.MAX_VALUE - total) throw new IllegalArgumentException("EPUB decompressed size overflow");
                total += size;
                if (total > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("EPUB cumulative decompressed size exceeds safety limit");
                }
            }
            if (!seen.contains("mimetype") || !seen.contains("META-INF/container.xml")) {
                throw new IllegalArgumentException("EPUB required container entries are missing");
            }
        }
    }

    private boolean unsafe(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) return true;
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) return true;
        for (String part : normalized.split("/", -1)) {
            if ("..".equals(part)) return true;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isISOControl(c) && !Character.isWhitespace(c)) return true;
            }
        }
        return false;
    }
}
