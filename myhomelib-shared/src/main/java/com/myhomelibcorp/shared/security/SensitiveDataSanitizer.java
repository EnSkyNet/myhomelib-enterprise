package com.myhomelibcorp.shared.security;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative, dependency-free redaction of credential-bearing network diagnostics. */
public final class SensitiveDataSanitizer {
    private static final String REDACTED_URI_VALUE = "%3Credacted%3E";
    private static final Set<String> SECRET_QUERY_KEYS = Set.of(
            "password", "pass", "passwd", "pwd", "token", "access_token", "refresh_token",
            "id_token", "key", "api_key", "apikey", "secret", "client_secret", "signature", "sig",
            "auth", "authorization", "access_key", "session_key", "session_token"
    );
    private static final Pattern BASIC_OR_BEARER = Pattern.compile(
            "(?i)\\b(Authorization\\s*[:=]\\s*)?(Basic|Bearer)\\s+[A-Za-z0-9._~+\\-/=]+"
    );
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|pass|token|access[_-]token|refresh[_-]token|id[_-]token|client[_-]secret|api[_-]?key|access[_-]key|session[_-]key|session[_-]token|secret|signature|sig|authorization|auth)\\s*[:=]\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^&\\s,;]+)"
    );

    private SensitiveDataSanitizer() { }

    /** Never echo URI user-info, fragments or opaque scheme-specific data. */
    public static String sanitizeUri(URI uri) {
        if (uri == null) return "";
        URI ascii = URI.create(uri.toASCIIString());
        String scheme = ascii.getScheme();
        if (ascii.isOpaque()) return scheme == null ? "<redacted URI>" : scheme + ":<redacted>";

        StringBuilder safe = new StringBuilder();
        if (scheme != null) safe.append(scheme).append(':');
        if (ascii.getRawAuthority() != null) {
            safe.append("//");
            String host = ascii.getHost();
            if (host == null) {
                // An unparseable authority may contain credentials. Fail closed.
                safe.append("<redacted-host>");
            } else {
                safe.append(host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host);
                if (ascii.getPort() >= 0) safe.append(':').append(ascii.getPort());
            }
        }
        if (ascii.getRawPath() != null) safe.append(ascii.getRawPath());
        if (ascii.getRawQuery() != null) safe.append('?').append(sanitizeQuery(ascii.getRawQuery()));
        return safe.toString();
    }

    public static String sanitizeText(String value) {
        if (value == null || value.isBlank()) return value;
        String safe = BASIC_OR_BEARER.matcher(value).replaceAll("$1$2 <redacted>");
        return KEY_VALUE_SECRET.matcher(safe).replaceAll("$1=<redacted>");
    }

    /** Both '&' and ';' are accepted query separators by HTTP frameworks. */
    private static String sanitizeQuery(String rawQuery) {
        StringBuilder out = new StringBuilder(rawQuery.length());
        int start = 0;
        for (int i = 0; i <= rawQuery.length(); i++) {
            if (i < rawQuery.length() && rawQuery.charAt(i) != '&' && rawQuery.charAt(i) != ';') continue;
            String part = rawQuery.substring(start, i);
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            out.append(key);
            if (equals >= 0) {
                out.append('=');
                out.append(isSensitiveKey(key) ? REDACTED_URI_VALUE : part.substring(equals + 1));
            }
            if (i < rawQuery.length()) out.append(rawQuery.charAt(i));
            start = i + 1;
        }
        return out.toString();
    }

    private static boolean isSensitiveKey(String rawKey) {
        String decoded = rawKey;
        // Encoded keys occur in redirects and proxy logs. Limit decoding depth.
        // Unparseable keys fail closed rather than risking exposure.
        for (int depth = 0; depth < 4; depth++) {
            String normalized = decoded.toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_').trim();
            if (SECRET_QUERY_KEYS.contains(normalized)
                    || normalized.endsWith("_token") || normalized.endsWith("_secret")
                    || normalized.endsWith("_password") || normalized.endsWith("_api_key")) return true;
            if (decoded.indexOf('%') < 0) return false;
            try {
                decoded = URLDecoder.decode(decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEncoding) {
                return true;
            }
        }
        return decoded.indexOf('%') >= 0; // Any remaining unknown encoding is secret-sensitive.
    }
}
