package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.shared.util.AppPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative sanitizer for diagnostic text. It intentionally prefers privacy over perfect log fidelity. */
final class SupportBundleSanitizer {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://[^\\s\\\"'<>]+");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.+-])[\\w.+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|secret|token|api[_-]?key|credential|authorization|auth|encryption\\.key)\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;|]+)");
    private static final Pattern PRIVATE_FIELD = Pattern.compile(
            "(?i)\\b(book(?:title)?|title|author|filename|archiveentry|collectionroot|collectionname)\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,;|]+)");
    // Once a user-home absolute path is detected, redact the rest of that line. That deliberately trades detail for privacy
    // because book/collection names commonly occur in the path suffix and cannot be safely inferred token-by-token.
    private static final Pattern WINDOWS_USER_PATH = Pattern.compile("(?i)(?<![A-Z0-9_])[A-Z]:\\\\Users\\\\.*");
    private static final Pattern UNIX_USER_PATH = Pattern.compile("(?<![A-Za-z0-9])/(?:home|Users)/.*");

    private final List<PathReplacement> exactPaths;

    SupportBundleSanitizer() {
        List<PathReplacement> paths = new ArrayList<>();
        add(paths, AppPaths.dataDir(), "<DATA_DIR>");
        add(paths, AppPaths.launchDir(), "<LAUNCH_DIR>");
        add(paths, pathProperty("user.home"), "<USER_HOME>");
        add(paths, pathProperty("java.io.tmpdir"), "<TEMP_DIR>");
        paths.sort(Comparator.comparingInt((PathReplacement p) -> p.value().length()).reversed());
        this.exactPaths = List.copyOf(paths);
    }

    String sanitize(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                out.append(sanitizeLine(text.substring(start, i)));
                if (i < text.length()) out.append('\n');
                start = i + 1;
            }
        }
        return out.toString();
    }

    String sanitizeLine(String line) {
        String result = line == null ? "" : line;
        for (PathReplacement replacement : exactPaths) {
            result = replaceIgnoreCase(result, replacement.value(), replacement.token());
            String slash = replacement.value().replace('\\', '/');
            if (!slash.equals(replacement.value())) result = replaceIgnoreCase(result, slash, replacement.token());
        }
        result = URL.matcher(result).replaceAll("<URL_REDACTED>");
        result = EMAIL.matcher(result).replaceAll("<EMAIL_REDACTED>");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll(m -> m.group(1) + "=<REDACTED>");
        result = PRIVATE_FIELD.matcher(result).replaceAll(m -> m.group(1) + "=<REDACTED>");
        result = WINDOWS_USER_PATH.matcher(result).replaceAll("<PATH_REDACTED>");
        result = UNIX_USER_PATH.matcher(result).replaceAll("<PATH_REDACTED>");
        return result;
    }

    private static String replaceIgnoreCase(String input, String literal, String replacement) {
        if (literal == null || literal.isBlank()) return input;
        return Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(input).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static Path pathProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value).toAbsolutePath().normalize(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static void add(List<PathReplacement> target, Path path, String token) {
        if (path == null) return;
        String value = path.toAbsolutePath().normalize().toString();
        if (!value.isBlank()) target.add(new PathReplacement(value, token));
    }

    private record PathReplacement(String value, String token) { }
}
