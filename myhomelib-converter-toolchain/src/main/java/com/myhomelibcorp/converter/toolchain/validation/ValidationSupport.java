package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class ValidationSupport {
    private ValidationSupport() { }

    static long validateRegularFile(Path file, long maxOutputBytes) throws ConversionToolchainException {
        if (file == null) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "Converter did not create a safe regular output file");
        }
        final Path safe;
        try {
            safe = ConversionPathSafety.requireSafeRegularFile(file, "Conversion output validation");
        } catch (IOException unsafe) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "Converter did not create a safe regular output file", unsafe);
        }
        try {
            long size = Files.size(safe);
            if (size <= 0) {
                throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                        "Converter produced an empty output file");
            }
            if (maxOutputBytes > 0 && size > maxOutputBytes) {
                throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                        "Conversion output exceeds the configured size limit");
            }
            return size;
        } catch (IOException e) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                    "Cannot inspect conversion output", e);
        }
    }

    static boolean unsafeZipName(String name) {
        if (name == null || name.isBlank()) return true;
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) return true;
        for (String part : normalized.split("/")) if ("..".equals(part)) return true;
        return false;
    }
}
