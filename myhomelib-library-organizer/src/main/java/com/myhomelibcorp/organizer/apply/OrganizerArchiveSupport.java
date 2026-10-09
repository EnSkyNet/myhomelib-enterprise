package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Canonical safe ZIP-entry access for organizer apply/rollback operations. */
final class OrganizerArchiveSupport {
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private OrganizerArchiveSupport() {}

    static ZipArchiveEntry requireEntry(ZipFile zip, String expectedName) {
        if (unsafeEntryName(expectedName)) throw new IllegalArgumentException("Unsafe archive entry name");
        ZipArchiveEntry selected = null;
        int inspected = 0;
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                throw new IllegalStateException("Archive entry-count limit exceeded");
            }
            ZipArchiveEntry entry = entries.nextElement();
            if (unsafeEntryName(entry.getName())) {
                throw new IllegalStateException("Archive contains unsafe entry path");
            }
            if (!entry.isDirectory() && expectedName.equals(entry.getName())) {
                if (selected != null) throw new IllegalStateException("Archive contains duplicate selected entry name");
                selected = entry;
            }
        }
        if (selected == null) throw new IllegalStateException("Archive entry disappeared: " + expectedName);
        validateEntry(selected);
        return selected;
    }

    static String sha256(Path archive, String sourceEntry) throws Exception {
        Path safeArchive = OrganizerPathSafety.canonicalExistingFile(archive, "Organizer source archive");
        try (ZipFile zip = SafeZipSupport.openDetected(safeArchive, "Organizer source archive")) {
            ZipArchiveEntry entry = requireEntry(zip, sourceEntry);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            long total = 0L;
            try (InputStream input = zip.getInputStream(entry)) {
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read <= 0) continue;
                    total = checkedAdd(total, read);
                    digest.update(buffer, 0, read);
                }
            }
            verifyActualSize(entry, total);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    static long size(Path archive, String sourceEntry) throws Exception {
        Path safeArchive = OrganizerPathSafety.canonicalExistingFile(archive, "Organizer source archive");
        try (ZipFile zip = SafeZipSupport.openDetected(safeArchive, "Organizer source archive")) {
            ZipArchiveEntry entry = requireEntry(zip, sourceEntry);
            if (entry.getSize() >= 0) return entry.getSize();
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long total = 0L;
            try (InputStream input = zip.getInputStream(entry)) {
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) total = checkedAdd(total, read);
                }
            }
            return total;
        }
    }

    static long copyEntry(
            ZipFile zip,
            ZipArchiveEntry entry,
            OutputStream output,
            BooleanSupplier cancelled) throws Exception {
        validateEntry(entry);
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0L;
        try (InputStream input = zip.getInputStream(entry)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancellationException("Archive extraction cancelled");
                }
                if (read <= 0) continue;
                total = checkedAdd(total, read);
                output.write(buffer, 0, read);
            }
        }
        verifyActualSize(entry, total);
        return total;
    }

    static void validateEntry(ZipArchiveEntry entry) {
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Archive entry is not a file");
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
            throw new IllegalStateException("Archive entry exceeds 512 MiB limit");
        }
        if (ArchiveSafetyLimits.exceedsCompressionRatio(entry.getSize(), entry.getCompressedSize())) {
            throw new IllegalStateException("Archive entry compression ratio exceeds safety limit");
        }
    }

    static boolean unsafeEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\', '/').split("/")) {
            if ("..".equals(part)) return true;
        }
        return false;
    }

    private static long checkedAdd(long current, int delta) {
        if (delta < 0 || current > ArchiveSafetyLimits.MAX_ENTRY_BYTES - delta) {
            throw new IllegalStateException("Archive entry exceeds 512 MiB limit");
        }
        return current + delta;
    }

    private static void verifyActualSize(ZipArchiveEntry entry, long actual) {
        if (entry.getSize() >= 0 && actual != entry.getSize()) {
            throw new IllegalStateException("Archive entry size mismatch");
        }
    }
}
