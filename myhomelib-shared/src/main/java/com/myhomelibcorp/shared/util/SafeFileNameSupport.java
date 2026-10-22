package com.myhomelibcorp.shared.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Cross-platform sanitization and validation for filesystem path components. */
public final class SafeFileNameSupport {
    private static final int MAX_COLLISION_COMPONENT_BYTES = 240;

    private SafeFileNameSupport() { }

    /**
     * Sanitizes one path component and caps its UTF-8 representation.
     *
     * <p>The byte cap matters on filesystems such as ext4 where a component is limited by bytes,
     * not Java character count. Windows-reserved DOS device names are prefixed with an underscore.
     * An empty string means that no usable component remained.</p>
     */
    public static String sanitizeComponent(String value, int maxUtf8Bytes) {
        if (value == null) return "";
        if (maxUtf8Bytes < 8) throw new IllegalArgumentException("maxUtf8Bytes must be at least 8");
        String sanitized = normalizeNfc(value)
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("[ .]+$", "");
        if (sanitized.equals(".") || sanitized.equals("..") || sanitized.isBlank()) return "";
        if (isWindowsReserved(sanitized)) sanitized = "_" + sanitized;
        sanitized = truncateUtf8(sanitized, maxUtf8Bytes).replaceAll("[ .]+$", "");
        return sanitized.equals(".") || sanitized.equals("..") ? "" : sanitized;
    }

    public static String sanitizeFileStem(String value, int maxUtf8Bytes) {
        return sanitizeComponent(value, maxUtf8Bytes);
    }

    public static String sanitizeDirectoryName(String value, int maxUtf8Bytes) {
        return sanitizeComponent(value, maxUtf8Bytes);
    }

    /** Truncates the prefix while preserving the requested suffix whenever it fits the byte budget. */
    public static String truncatePreservingSuffix(String value, String suffix, int maxUtf8Bytes) {
        Objects.requireNonNull(value, "value");
        suffix = suffix == null ? "" : suffix;
        if (maxUtf8Bytes < 1) throw new IllegalArgumentException("maxUtf8Bytes must be positive");
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        if (suffixBytes.length >= maxUtf8Bytes) return truncateUtf8(suffix, maxUtf8Bytes);
        int prefixBudget = maxUtf8Bytes - suffixBytes.length;
        String prefix = value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
        return truncateUtf8(prefix, prefixBudget) + suffix;
    }

    /**
     * Validates and normalizes a relative path. Absolute paths, traversal and empty components are rejected.
     */
    public static Path validateRelativePath(Path relative) {
        Objects.requireNonNull(relative, "relative");
        if (relative.isAbsolute()) throw new IllegalArgumentException("Path must be relative: " + relative);
        // Verify the unnormalized components: normalization must not erase
        // input traversal such as shelf/../book.fb2 before validation.
        for (Path component : relative) {
            String name = component.toString();
            if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
                throw new IllegalArgumentException("Unsafe relative path: " + relative);
            }
        }
        Path normalized = relative.normalize();
        if (normalized.getNameCount() == 0 || normalized.toString().isBlank()) {
            throw new IllegalArgumentException("Relative path must not be empty");
        }
        return normalized;
    }

    /**
     * Returns the desired path when it is free; otherwise appends a stable suffix before the extension.
     * This helper never overwrites an existing path.
     */
    public static Path resolveCollision(Path desiredTarget, String stableSuffix) {
        Objects.requireNonNull(desiredTarget, "desiredTarget");
        if (!Files.exists(desiredTarget, LinkOption.NOFOLLOW_LINKS)) return desiredTarget;
        String suffix = sanitizeComponent(stableSuffix, 64);
        if (suffix.isBlank()) suffix = "copy";
        String fileName = desiredTarget.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path parent = desiredTarget.getParent();
        Path candidate = collisionCandidate(parent, stem, ext, " [" + suffix + "]");
        int counter = 2;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = collisionCandidate(parent, stem, ext, " [" + suffix + "-" + counter++ + "]");
        }
        return candidate;
    }

    /** Keep the extension and stable collision token, trimming only the stem by UTF-8 bytes. */
    private static Path collisionCandidate(Path parent, String stem, String extension, String marker) {
        int suffixBytes = (marker + extension).getBytes(StandardCharsets.UTF_8).length;
        int stemBudget = MAX_COLLISION_COMPONENT_BYTES - suffixBytes;
        if (stemBudget < 1) {
            throw new IllegalArgumentException("Collision suffix and extension exceed safe filename limit");
        }
        String clippedStem = truncateUtf8(stem, stemBudget);
        if (clippedStem.isEmpty()) clippedStem = "_";
        return (parent == null ? Path.of("") : parent).resolve(clippedStem + marker + extension);
    }

    public static String normalizeNfc(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static boolean isWindowsReserved(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        // Win32 treats a space before the extension as a trailing device-name space.
        String stem = (dot < 0 ? upper : upper.substring(0, dot)).stripTrailing();
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")) return true;
        if (stem.length() == 4 && (stem.startsWith("COM") || stem.startsWith("LPT"))) {
            char digit = stem.charAt(3);
            // Windows also reserves the ISO-8859-1 superscript digits 1, 2 and 3.
            return digit >= '1' && digit <= '9' || digit == '\u00B9' || digit == '\u00B2' || digit == '\u00B3';
        }
        return false;
    }

    public static String truncateUtf8(String value, int maxBytes) {
        Objects.requireNonNull(value, "value");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
        int used = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int bytes = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (used + bytes > maxBytes) break;
            used += bytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }
}
