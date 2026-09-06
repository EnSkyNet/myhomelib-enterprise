package com.myhomelibcorp.shared.format;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Declarative cross-layer contract for one supported file format or container family. */
public record SupportedFormat(
        String id,
        String searchFormat,
        String displayName,
        Set<String> extensions,
        Set<String> mimeTypes,
        Family family,
        ImportMode importMode,
        boolean metadataSupported,
        boolean readerSupported,
        boolean coverSupported,
        boolean fullTextSupported) {

    public enum Family { BOOK, CATALOG, ARCHIVE }
    public enum ImportMode { NATIVE, GENERIC, CATALOG, ARCHIVE, NONE }

    public SupportedFormat {
        id = normalizeId(id);
        searchFormat = searchFormat == null || searchFormat.isBlank()
                ? "UNKNOWN" : searchFormat.trim().toUpperCase(Locale.ROOT);
        displayName = displayName == null || displayName.isBlank() ? id.toUpperCase(Locale.ROOT) : displayName.trim();
        extensions = normalizedExtensions(extensions);
        mimeTypes = immutableTrimmed(mimeTypes);
        family = family == null ? Family.BOOK : family;
        importMode = importMode == null ? ImportMode.NONE : importMode;
        if (extensions.isEmpty()) throw new IllegalArgumentException("Format must declare at least one extension: " + id);
    }

    public boolean importSupported() { return importMode != ImportMode.NONE; }

    public boolean matchesFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String normalized = fileName.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        for (String extension : extensions) {
            if (normalized.endsWith("." + extension)) return true;
        }
        return false;
    }

    public Set<String> chooserPatterns() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String extension : extensions) result.add("*." + extension);
        return Collections.unmodifiableSet(result);
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Format id is required");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizedExtensions(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String ext = value.trim().toLowerCase(Locale.ROOT);
                while (ext.startsWith(".")) ext = ext.substring(1);
                if (!ext.isBlank()) result.add(ext);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> immutableTrimmed(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
