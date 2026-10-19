package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Give the single FB2 member of a device-exported FB2.ZIP the same stem as the
 * final archive. Works for both existing online-library archives and newly
 * converted ZIPs; never changes the original library artifact or FB2 bytes.
 */
final class Fb2ZipExportNameNormalizer {
    private static final String ARCHIVE_SUFFIX = ".fb2.zip";
    private static final int BUFFER_SIZE = 64 * 1024;

    private Fb2ZipExportNameNormalizer() { }

    static Path normalize(Path stagedArchive, Path finalTarget, Path workspace,
                          BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        String archiveName = finalTarget.getFileName().toString();
        if (!archiveName.toLowerCase(Locale.ROOT).endsWith(ARCHIVE_SUFFIX)) {
            throw new IOException("Expected FB2.ZIP export path: " + archiveName);
        }
        // The final name includes any collision suffix (" (1)") chosen by export.
        String entryName = archiveName.substring(0, archiveName.length() - ARCHIVE_SUFFIX.length()) + ".fb2";
        if (entryName.equals(".fb2")) throw new IOException("Empty FB2.ZIP export name");
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("FB2.ZIP export cancelled");

        try (ZipFile zip = SafeZipSupport.openDetected(stagedArchive, "FB2.ZIP export source")) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            if (!entries.hasMoreElements()) throw new IOException("FB2.ZIP archive has no book entry");
            ZipArchiveEntry source = entries.nextElement();
            // FB2.ZIP is one book in one ZIP. Never silently discard extra files.
            if (entries.hasMoreElements() || source.isDirectory() || source.isUnixSymlink()) {
                throw new IOException("FB2.ZIP export requires exactly one regular book entry");
            }
            String originalName = source.getName();
            String lowercase = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
            if (!lowercase.endsWith(".fb2") && !lowercase.endsWith(".fbd")) {
                throw new IOException("FB2.ZIP archive does not contain an FB2 book");
            }
            if (!zip.canReadEntryData(source)
                    || ArchiveSafetyLimits.declaredEntryTooLarge(source.getSize())
                    || ArchiveSafetyLimits.exceedsCompressionRatio(source.getSize(), source.getCompressedSize())) {
                throw new IOException("FB2.ZIP entry cannot be decoded within archive safety limits");
            }
            if (entryName.equals(originalName)) return stagedArchive;

            Path normalized = workspace.resolve("normalized-entry.fb2.zip");
            CRC32 checksum = new CRC32();
            long copied = 0;
            try (InputStream input = zip.getInputStream(source);
                 ZipOutputStream output = new ZipOutputStream(
                         SafeFilePublication.openFreshOutputStream(normalized, "Renamed FB2.ZIP export"),
                         StandardCharsets.UTF_8)) {
                output.putNextEntry(new ZipEntry(entryName));
                byte[] buffer = new byte[BUFFER_SIZE];
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (cancelled.getAsBoolean()) {
                        throw new java.util.concurrent.CancellationException("FB2.ZIP export cancelled");
                    }
                    copied += n;
                    if (copied > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                        throw new IOException("FB2.ZIP uncompressed entry exceeds safety limit");
                    }
                    checksum.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
                output.closeEntry();
            }
            if (source.getSize() >= 0 && copied != source.getSize()) {
                throw new IOException("FB2.ZIP entry size mismatch");
            }
            if (source.getCrc() >= 0 && checksum.getValue() != source.getCrc()) {
                throw new IOException("FB2.ZIP entry CRC mismatch");
            }
            if (!Files.isRegularFile(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Renamed FB2.ZIP export was not created");
            }
            return normalized;
        }
    }
}
