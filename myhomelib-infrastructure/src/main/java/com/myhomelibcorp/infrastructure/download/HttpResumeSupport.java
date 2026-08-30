package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.Sha256Support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, credential-safe state for resumable HTTP downloads. */
final class HttpResumeSupport {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private HttpResumeSupport() { }

    static void write(Path meta, URI uri, HttpResponse<?> response) throws IOException {
        String validator = response.headers().firstValue("ETag")
                .filter(value -> !value.isBlank())
                .orElseGet(() -> response.headers().firstValue("Last-Modified").orElse(""));
        Properties properties = new Properties();
        properties.setProperty("sourceSha256", sourceHash(uri));
        properties.setProperty("validator", validator);
        Path temp = meta.resolveSibling(meta.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(out, "MyHomeLib v7.1 resumable download metadata; no URL/credentials stored");
        }
        AtomicFileSupport.moveReplacing(temp, meta);
    }

    static ResumeMetadata read(Path meta, URI uri) {
        if (!Files.isRegularFile(meta)) return null;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(meta)) {
            properties.load(in);
            if (!sourceHash(uri).equals(properties.getProperty("sourceSha256", ""))) return null;
            return new ResumeMetadata(properties.getProperty("validator", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    static void validateContentRange(HttpResponse<?> response, long expectedStart, URI uri) throws IOException {
        String value = response.headers().firstValue("Content-Range").orElse("").trim();
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw invalidContentRange(uri, value, expectedStart, null);
        }
        try {
            long actualStart = Long.parseLong(matcher.group(1));
            if (actualStart != expectedStart) {
                throw invalidContentRange(uri, value, expectedStart, actualStart);
            }
        } catch (NumberFormatException e) {
            throw invalidContentRange(uri, value, expectedStart, null);
        }
    }

    static long expectedTotal(HttpResponse<?> response, long offset) {
        if (response.statusCode() == 206) {
            String value = response.headers().firstValue("Content-Range").orElse("").trim();
            Matcher matcher = CONTENT_RANGE.matcher(value);
            if (matcher.matches() && !"*".equals(matcher.group(3))) {
                try {
                    return Long.parseLong(matcher.group(3));
                } catch (NumberFormatException ignored) {
                    // Fall back to Content-Length below.
                }
            }
        }
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return length >= 0 ? offset + length : -1;
    }

    private static IOException invalidContentRange(URI uri, String value, long expectedStart, Long actualStart) {
        String detail = actualStart == null ? value : actualStart + " (очікувалось " + expectedStart + ")";
        return new IOException("Некоректний Content-Range для resume "
                + SensitiveDataSanitizer.sanitizeUri(uri) + ": " + detail);
    }

    private static String sourceHash(URI uri) {
        return Sha256Support.utf8(uri.toASCIIString());
    }

    record ResumeMetadata(String validator) { }
}
