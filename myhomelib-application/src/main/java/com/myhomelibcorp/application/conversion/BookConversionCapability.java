package com.myhomelibcorp.application.conversion;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral conversion edge used to build the conversion capability matrix.
 * Source formats are normalized lower-case names without a leading dot; {@code *}
 * means that the provider decides dynamically through {@code BookConverter.supports(...)}.
 */
public record BookConversionCapability(Set<String> sourceFormats, String targetFormat, String targetExtension) {

    public BookConversionCapability {
        LinkedHashSet<String> normalizedSources = new LinkedHashSet<>();
        if (sourceFormats != null) {
            for (String source : sourceFormats) {
                String normalized = normalizeFormat(source);
                if (!normalized.isBlank()) normalizedSources.add(normalized);
            }
        }
        sourceFormats = normalizedSources.isEmpty() ? Set.of("*") : Set.copyOf(normalizedSources);
        targetFormat = normalizeFormat(targetFormat);
        if (targetFormat.isBlank()) throw new IllegalArgumentException("Target format cannot be blank");
        targetExtension = normalizeExtension(targetExtension, targetFormat);
    }

    public static BookConversionCapability anySource(String targetFormat, String targetExtension) {
        return new BookConversionCapability(Set.of("*"), targetFormat, targetExtension);
    }

    public boolean supportsSource(String sourceFormat) {
        String normalized = normalizeFormat(sourceFormat);
        return sourceFormats.contains("*") || sourceFormats.contains(normalized);
    }

    public boolean produces(String format) {
        return targetFormat.equals(normalizeFormat(format));
    }

    public static String normalizeFormat(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) normalized = normalized.substring(1);
        return switch (normalized) {
            case "fbd" -> "fb2";
            case "text" -> "txt";
            case "fb2zip", "fb2_zip", "fb2.zip" -> "fb2_zip";
            default -> normalized;
        };
    }

    private static String normalizeExtension(String extension, String targetFormat) {
        String value = Objects.toString(extension, "").trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) value = "." + targetFormat.replace('_', '.');
        return value.startsWith(".") ? value : "." + value;
    }
}
