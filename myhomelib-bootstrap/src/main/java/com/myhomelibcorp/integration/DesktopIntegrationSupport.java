package com.myhomelibcorp.integration;

import com.myhomelibcorp.shared.util.ThrowableText;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.plugin.api.PluginApiVersion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Pure validation/encoding helpers kept out of the stateful desktop integration coordinator. */
final class DesktopIntegrationSupport {
    private DesktopIntegrationSupport() { }

    static Map<String, Long> advanceCursors(Map<String, Long> original, List<ChangeSet> pulled) {
        Map<String, Long> result = new HashMap<>(original == null ? Map.of() : original);
        if (pulled != null) {
            for (ChangeSet set : pulled) {
                if (set == null) continue;
                result.merge(set.sourceDeviceId(), set.sequence(), Math::max);
            }
        }
        return result;
    }

    static URI validateWebDavEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNullElse(value, "").trim()).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некоректна URL-адреса WebDAV", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || isLoopbackHttp(uri))) {
            throw new IllegalArgumentException("WebDAV має використовувати HTTPS; HTTP дозволено лише для localhost");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("Не додавайте логін або пароль у URL WebDAV");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("У URL WebDAV відсутній сервер");
        String text = uri.toString();
        if (!text.endsWith("/")) text += "/";
        return URI.create(text);
    }

    static boolean sameEndpoint(String left, String right) {
        try { return validateWebDavEndpoint(left).equals(validateWebDavEndpoint(right)); }
        catch (Exception ignored) { return Objects.equals(left, right); }
    }

    static String safeId(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return normalized.isBlank() ? fallback : normalized.substring(0, Math.min(80, normalized.length()));
    }

    static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("У маніфесті плагіна відсутнє поле " + key);
        return value;
    }

    static PluginApiVersion parseApiVersion(String value) {
        String[] parts = value.trim().split("\\.");
        if (parts.length != 2) throw new IllegalArgumentException("Версія Plugin API має формат major.minor: " + value);
        return new PluginApiVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    static <E extends Enum<E>> Set<E> parseEnumSet(String raw, Class<E> type) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return Set.of();
        EnumSet<E> result = EnumSet.noneOf(type);
        for (String token : value.split(",")) {
            String normalized = token.trim();
            if (!normalized.isBlank()) result.add(Enum.valueOf(type, normalized));
        }
        return Set.copyOf(result);
    }

    static String sanitizeFilePart(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    static String gzipBase64(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(bytes); }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static byte[] gunzipBase64(String encoded, int maxBytes, String tooLargeMessage) throws IOException {
        byte[] compressed;
        try { compressed = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IOException("Некоректне Base64-кодування пакета", e); }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, Math.max(8192, compressed.length * 2)))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = gzip.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > maxBytes) throw new IOException(tooLargeMessage);
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 недоступний", impossible);
        }
    }

    static byte[] readBounded(Path path, int maxBytes, String tooLargeMessage) throws IOException {
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException(tooLargeMessage);
            return bytes;
        }
    }

    static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    static String safeMessage(Throwable failure) {
        if (failure == null) return "Невідома помилка";
        String message = ThrowableText.rootMessage(failure, "Невідома помилка");
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 600 ? clean : clean.substring(0, safeTextCut(clean, 600));
    }

    static int safeTextCut(String value, int requestedEnd) {
        if (value == null || value.isEmpty() || requestedEnd <= 0) return 0;
        int end = Math.min(requestedEnd, value.length());
        if (end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        while (end > 0 && end < value.length()) {
            int type = Character.getType(value.codePointAt(end));
            if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                break;
            }
            end -= Character.charCount(value.codePointBefore(end));
        }
        return end;
    }

    private static boolean isLoopbackHttp(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
