package com.myhomelibcorp.infrastructure.archive;

import com.myhomelibcorp.shared.util.FileNameSupport;

import java.text.Normalizer;
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

    public static boolean sameLogicalPath(String left, String right) {
        String normalizedLeft = Normalizer.normalize(
                FileNameSupport.decodeHashUnicodeEscapes(normalizePath(left)), Normalizer.Form.NFC);
        String normalizedRight = Normalizer.normalize(
                FileNameSupport.decodeHashUnicodeEscapes(normalizePath(right)), Normalizer.Form.NFC);
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
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
        String normalizedFileName = Normalizer.normalize(fileName, Normalizer.Form.NFC);
        String normalizedToken = Normalizer.normalize(token, Normalizer.Form.NFC);
        var pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(normalizedToken),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        var matcher = pattern.matcher(normalizedFileName);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            boolean leftBoundary = start == 0
                    || !Character.isLetterOrDigit(normalizedFileName.codePointBefore(start));
            boolean rightBoundary = end == normalizedFileName.length()
                    || !Character.isLetterOrDigit(normalizedFileName.codePointAt(end));
            if (leftBoundary && rightBoundary) return true;
        }
        return false;
    }
}
