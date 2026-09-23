package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

@Component
public class DocxOutputValidator implements ConversionOutputValidator {
    @Override public boolean supports(String format) { return "docx".equalsIgnoreCase(format); }

    @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        ValidationSupport.validateRegularFile(file, maxOutputBytes);
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry("word/document.xml") == null) {
                throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                        "DOCX package is missing required entries");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            int count = 0;
            long total = 0L;
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new UnsafeZipEntry("too many ZIP entries");
                if (ValidationSupport.unsafeZipName(name)) throw new UnsafeZipEntry(name);
                if (!seen.add(name)) throw new UnsafeZipEntry("duplicate ZIP entry: " + name);
                if (entry.getMethod() != java.util.zip.ZipEntry.STORED && entry.getMethod() != java.util.zip.ZipEntry.DEFLATED) {
                    throw new UnsafeZipEntry("unsupported ZIP method: " + name);
                }
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size < 0 || ArchiveSafetyLimits.declaredEntryTooLarge(size)) {
                    throw new UnsafeZipEntry("unsafe ZIP entry size: " + name);
                }
                if (Long.MAX_VALUE - total < size || (total += size) > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                    throw new UnsafeZipEntry("uncompressed ZIP payload exceeds safety limit");
                }
                if (ArchiveSafetyLimits.exceedsCompressionRatio(size, entry.getCompressedSize())) {
                    throw new UnsafeZipEntry("suspicious ZIP compression ratio: " + name);
                }
            }
        } catch (UnsafeZipEntry e) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "DOCX contains an unsafe ZIP entry: " + e.getMessage(), e);
        } catch (ConversionToolchainException e) { throw e; }
        catch (Exception e) { throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "DOCX output cannot be parsed", e); }
    }

    private static final class UnsafeZipEntry extends RuntimeException { UnsafeZipEntry(String name) { super(name); } }
}
