package com.myhomelibcorp.infrastructure.archive;

import java.util.Locale;

/** Shared, side-effect-free matching rules for logical files stored inside archives. */
public final class ArchiveEntryNameSupport {
    private ArchiveEntryNameSupport() { }

    public static String normalizePath(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    public static boolean isFb2(String value) {
        return normalizePath(value).toLowerCase(Locale.ROOT).endsWith(".fb2");
    }

    public static String baseName(String value) {
        String normalized = normalizePath(value);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    public static String stripFb2Extension(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.toLowerCase(Locale.ROOT).endsWith(".fb2")
                ? normalized.substring(0, normalized.length() - 4)
                : normalized;
    }

    public static boolean containsDelimitedToken(String fileName, String token) {
        if (fileName == null || token == null || token.isBlank()) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        String wanted = token.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int index = lower.indexOf(wanted, from);
            if (index < 0) return false;
            int end = index + wanted.length();
            boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1));
            boolean rightBoundary = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from = index + 1;
        }
    }
}
