package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.application.conversion.BookConversionCapability;

import java.util.Objects;

public record ConversionStep(
        ConversionEngineId engineId,
        String sourceFormat,
        String targetFormat,
        String targetExtension,
        int qualityRank,
        boolean lossy,
        int costRank) {

    public ConversionStep {
        Objects.requireNonNull(engineId, "engineId");
        sourceFormat = BookConversionCapability.normalizeFormat(sourceFormat);
        targetFormat = BookConversionCapability.normalizeFormat(targetFormat);
        if (sourceFormat.isBlank() || targetFormat.isBlank()) {
            throw new IllegalArgumentException("Conversion formats cannot be blank");
        }
        targetExtension = normalizeExtension(targetExtension, targetFormat);
    }

    private static String normalizeExtension(String value, String format) {
        String result = value == null || value.isBlank() ? "." + format : value.trim().toLowerCase();
        return result.startsWith(".") ? result : "." + result;
    }
}
