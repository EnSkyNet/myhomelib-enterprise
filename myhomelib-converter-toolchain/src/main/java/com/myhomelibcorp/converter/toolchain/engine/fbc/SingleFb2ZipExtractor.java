package com.myhomelibcorp.converter.toolchain.engine.fbc;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Extracts the one-and-only FB2 payload from an FB2.ZIP into an owned staging file.
 * No archive entry path is ever materialized, which removes the path-traversal write primitive.
 */
final class SingleFb2ZipExtractor {
    private static final int BUFFER_SIZE = 64 * 1024;

    Path extract(Path archive, Path workspace, BooleanSupplier cancelled) throws ConversionToolchainException {
        if (archive == null || workspace == null) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "FB2.ZIP archive/workspace is missing");
        }
        Path zipPath = archive.toAbsolutePath().normalize();
        Path root = workspace.toAbsolutePath().normalize();
        try {
            root = ConversionPathSafety.createDirectoriesNoLinks(root, "FB2.ZIP staging workspace");
            try { zipPath = ConversionPathSafety.requireSafeRegularFile(zipPath, "FB2.ZIP source"); }
            catch (IOException unsafe) { throw new ConversionToolchainException(ConversionFailureKind.INVALID_INPUT, "FB2.ZIP source is not a safe regular file", unsafe); }
            checkCancelled(cancelled);
            try (ZipFile zip = SafeZipSupport.openDetected(zipPath, "FB2.ZIP source")) {
                ZipArchiveEntry selected = findSingleFb2(zip, cancelled);
                validateSelectedEntry(selected);
                Path staged = root.resolve(".mhl-fbc-input-" + UUID.randomUUID() + ".fb2").normalize();
                if (!staged.startsWith(root)) {
                    throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "Unsafe FB2.ZIP staging path");
                }
                try {
                    copyBounded(zip, selected, staged, cancelled);
                    validateCompressionRatio(selected, Files.size(staged));
                    return staged;
                } catch (Exception failure) {
                    try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
                    if (failure instanceof ConversionToolchainException toolchain) throw toolchain;
                    throw failure;
                }
            }
        } catch (ConversionToolchainException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw new ConversionToolchainException(ConversionFailureKind.INVALID_INPUT, "Cannot read FB2.ZIP archive", failure);
        } catch (Exception failure) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "Cannot stage FB2.ZIP payload", failure);
        }
    }

    private ZipArchiveEntry findSingleFb2(ZipFile zip, BooleanSupplier cancelled) throws ConversionToolchainException {
        ZipArchiveEntry selected = null;
        int inspected = 0;
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            checkCancelled(cancelled);
            if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2.ZIP contains too many entries");
            }
            ZipArchiveEntry entry = entries.nextElement();
            String name = entry.getName();
            if (unsafeEntryName(name)) {
                throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2.ZIP contains an unsafe entry path");
            }
            if (entry.isDirectory()) continue;
            if (name.toLowerCase(Locale.ROOT).endsWith(".fb2")) {
                if (selected != null) {
                    throw new ConversionToolchainException(ConversionFailureKind.UNSUPPORTED, "Multi-book FB2.ZIP is not a single-book conversion source");
                }
                selected = entry;
            }
        }
        if (selected == null) {
            throw new ConversionToolchainException(ConversionFailureKind.INVALID_INPUT, "FB2.ZIP contains no FB2 payload");
        }
        return selected;
    }

    private void validateSelectedEntry(ZipArchiveEntry entry) throws ConversionToolchainException {
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
            throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2 payload exceeds archive safety limit");
        }
        long compressed = entry.getCompressedSize();
        long uncompressed = entry.getSize();
        if (compressed > 0 && uncompressed > 0 && exceedsCompressionRatio(uncompressed, compressed)) {
            throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2.ZIP compression ratio exceeds safety limit");
        }
    }

    private void copyBounded(ZipFile zip, ZipArchiveEntry selected, Path staged, BooleanSupplier cancelled) throws Exception {
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = zip.getInputStream(selected);
             OutputStream out = ConversionPathSafety.openFreshOutputStream(staged, "FB2.ZIP staged payload")) {
            for (int read; (read = in.read(buffer)) >= 0;) {
                checkCancelled(cancelled);
                if (read == 0) continue;
                copied += read;
                if (copied > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2 payload exceeds archive safety limit while extracting");
                }
                out.write(buffer, 0, read);
            }
        }
        if (copied == 0) {
            throw new ConversionToolchainException(ConversionFailureKind.INVALID_INPUT, "FB2.ZIP payload is empty");
        }
    }

    private void validateCompressionRatio(ZipArchiveEntry entry, long actualBytes) throws ConversionToolchainException {
        long compressed = entry.getCompressedSize();
        if (compressed > 0 && exceedsCompressionRatio(actualBytes, compressed)) {
            throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED, "FB2.ZIP compression ratio exceeds safety limit");
        }
    }

    private boolean exceedsCompressionRatio(long uncompressed, long compressed) {
        if (compressed <= 0 || uncompressed <= 0) return false;
        if (compressed > Long.MAX_VALUE / ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) return false;
        return uncompressed > compressed * ArchiveSafetyLimits.MAX_COMPRESSION_RATIO;
    }

    private boolean unsafeEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) return true;
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) return true;
        for (String segment : normalized.split("/", -1)) {
            if ("..".equals(segment)) return true;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (Character.isISOControl(c) && !Character.isWhitespace(c)) return true;
            }
        }
        return false;
    }

    private void checkCancelled(BooleanSupplier cancelled) throws ConversionToolchainException {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new ConversionToolchainException(ConversionFailureKind.CANCELLED, "FB2.ZIP extraction cancelled");
        }
    }
}
