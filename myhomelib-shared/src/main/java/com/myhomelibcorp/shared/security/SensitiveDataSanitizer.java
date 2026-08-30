package com.myhomelibcorp.shared.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small dependency-free redaction helper for diagnostics and persisted error messages.
 * It is intentionally conservative: if URI parsing fails, the query is removed entirely.
 */
public final class SensitiveDataSanitizer {
    private static final Set<String> SECRET_QUERY_KEYS = Set.of(
            "password", "pass", "passwd", "pwd", "token", "access_token", "refresh_token",
            "key", "api_key", "apikey", "secret", "signature", "sig", "auth", "authorization"
    );
    private static final Pattern BASIC_OR_BEARER = Pattern.compile(
            "(?i)\\b(Authorization\\s*[:=]\\s*)?(Basic|Bearer)\\s+[A-Za-z0-9._~+\\-/=]+"
    );
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|pass|token|access_token|refresh_token|api[_-]?key|secret|signature|sig)\\s*[:=]\\s*([^&\\s,;]+)"
    );

    private SensitiveDataSanitizer() { }

    public static String sanitizeUri(URI uri) {
        if (uri == null) return "";
        String query = sanitizeQuery(uri.getRawQuery());
        try {
            URI safe = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getRawPath(), query, null);
            return safe.toASCIIString();
        } catch (URISyntaxException e) {
            String base = uri.getScheme() == null ? "" : uri.getScheme() + "://";
            base += uri.getHost() == null ? "<host>" : uri.getHost();
            if (uri.getPort() >= 0) base += ":" + uri.getPort();
            return base + (uri.getRawPath() == null ? "" : uri.getRawPath());
        }
    }

    public static String sanitizeText(String value) {
        if (value == null || value.isBlank()) return value;
        String safe = BASIC_OR_BEARER.matcher(value).replaceAll("$1$2 <redacted>");
        safe = KEY_VALUE_SECRET.matcher(safe).replaceAll("$1=<redacted>");
        return safe;
    }

    private static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String part : rawQuery.split("&", -1)) {
            if (out.length() > 0) out.append('&');
            int eq = part.indexOf('=');
            String rawKey = eq < 0 ? part : part.substring(0, eq);
            String normalized = rawKey.toLowerCase(Locale.ROOT).replace("-", "_");
            out.append(rawKey);
            if (eq >= 0) {
                out.append('=');
                out.append(SECRET_QUERY_KEYS.contains(normalized) ? "<redacted>" : part.substring(eq + 1));
            }
        }
        return out.toString();
    }
}
