package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import com.myhomelibcorp.shared.util.Sha256Support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, credential-safe state for resumable HTTP downloads. */
final class HttpResumeSupport {
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private HttpResumeSupport() { }

    static ResumeMetadata write(Path meta, URI uri, HttpResponse<?> response, String expectedMetaHash) throws IOException {
        String validator = resumeValidator(response);
        Properties properties = new Properties();
        properties.setProperty("sourceSha256", sourceHash(uri));
        properties.setProperty("validator", validator);
        Path absolute = meta.toAbsolutePath().normalize();
        String expectedHash = expectedMetaHash;
        if (expectedHash == null && Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("HTTP resume metadata appeared during the download: " + absolute);
        }
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                absolute.getParent(), ".mhl-resume-meta-", "HTTP resume metadata workspace");
        Path temp = workspace.resolve("metadata.properties");
        try {
            try (OutputStream out = SafeFilePublication.openFreshOutputStream(temp, "HTTP resume metadata staging")) {
                properties.store(out, "MyHomeLib 8.0.0 resumable download metadata; no URL/credentials stored");
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    temp, absolute, expectedHash, "HTTP resume metadata publication");
            return new ResumeMetadata(validator,
                    SafeFilePublication.sha256(absolute, "HTTP resume metadata"));
        } finally {
            SafeFilePublication.cleanupPrivateWorkspace(workspace);
        }
    }


    private static String resumeValidator(HttpResponse<?> response) {
        String etag = response.headers().firstValue("ETag")
                .map(String::trim)
                .orElse("");
        if (isStrongEntityTag(etag)) {
            return etag;
        }
        return response.headers().firstValue("Last-Modified")
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse("");
    }

    private static boolean isStrongEntityTag(String value) {
        return value != null
                && value.length() >= 2
                && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"';
    }

    static ResumeMetadata read(Path meta, URI uri) throws IOException {
        if (!Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) return null;
        String ownershipHash = SafeFilePublication.snapshotExistingTarget(meta, "HTTP resume metadata");
        if (ownershipHash == null) return null;
        Properties properties = new Properties();
        try (InputStream bounded = new java.io.ByteArrayInputStream(
                SafeFilePublication.readOwnedBytes(meta, ownershipHash, MAX_METADATA_BYTES, "HTTP resume metadata"))) {
            properties.load(bounded);
            if (!sourceHash(uri).equals(properties.getProperty("sourceSha256", ""))) return null;
            return new ResumeMetadata(properties.getProperty("validator", ""), ownershipHash);
        } catch (IOException e) {
            throw e;
        } catch (Exception invalid) {
            return null;
        }
    }

    static void validateResumeResponse(
            HttpResponse<?> response, long expectedStart, String expectedValidator, URI uri) throws IOException {
        validateContentRange(response, expectedStart, uri);
        validateEntityValidator(response, expectedValidator, uri);
    }

    static void validateContentRange(HttpResponse<?> response, long expectedStart, URI uri) throws IOException {
        String value = response.headers().firstValue("Content-Range").orElse("").trim();
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw invalidContentRange(uri, value, expectedStart, null);
        }
        try {
            long actualStart = Long.parseLong(matcher.group(1));
            long actualEnd = Long.parseLong(matcher.group(2));
            Long total = "*".equals(matcher.group(3)) ? null : Long.parseLong(matcher.group(3));
            if (actualStart != expectedStart || actualEnd < actualStart
                    || (total != null && (total <= actualEnd || actualStart >= total))) {
                throw invalidContentRange(uri, value, expectedStart, actualStart);
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength >= 0) {
                long rangeLength = actualEnd - actualStart + 1L;
                if (rangeLength <= 0 || contentLength != rangeLength) {
                    throw invalidContentRange(uri, value, expectedStart, actualStart);
                }
            }
        } catch (NumberFormatException e) {
            throw invalidContentRange(uri, value, expectedStart, null);
        }
    }

    private static void validateEntityValidator(
            HttpResponse<?> response, String expectedValidator, URI uri) throws IOException {
        if (expectedValidator == null || expectedValidator.isBlank()) return;
        String expected = expectedValidator.trim();
        boolean entityTag = expected.startsWith("\"") || expected.regionMatches(true, 0, "W/\"", 0, 3);
        String actual = response.headers().firstValue(entityTag ? "ETag" : "Last-Modified")
                .map(String::trim)
                .orElse("");
        if (!actual.isBlank() && !actual.equals(expected)) {
            throw new IOException("Змінено HTTP validator під час resume "
                    + SensitiveDataSanitizer.sanitizeUri(uri));
        }
    }

    static long expectedPartialBodyLength(HttpResponse<?> response) {
        if (response.statusCode() != 206) return -1L;
        String value = response.headers().firstValue("Content-Range").orElse("").trim();
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) return -1L;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return end >= start ? end - start + 1L : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
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
        if (length < 0) return -1;
        if (offset < 0 || offset > Long.MAX_VALUE - length) return Long.MAX_VALUE;
        return offset + length;
    }

    private static IOException invalidContentRange(URI uri, String value, long expectedStart, Long actualStart) {
        String detail = actualStart == null ? value : actualStart + " (очікувалось " + expectedStart + ")";
        return new IOException("Некоректний Content-Range для resume "
                + SensitiveDataSanitizer.sanitizeUri(uri) + ": " + detail);
    }

    private static String sourceHash(URI uri) {
        return Sha256Support.utf8(uri.toASCIIString());
    }

    record ResumeMetadata(String validator, String ownershipHash) { }
}
