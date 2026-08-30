package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Shared SHA-256 primitives used by import, download and source-monitor fingerprints. */
public final class Sha256Support {
    private static final int DEFAULT_BUFFER_BYTES = 256 * 1024;

    private Sha256Support() { }

    public static String utf8(String value) {
        MessageDigest digest = newDigest();
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return finish(digest);
    }

    public static String file(Path file) throws IOException {
        return file(file, () -> false).orElseThrow();
    }

    /** Returns empty only when the supplied cancellation predicate becomes true. */
    public static Optional<String> file(Path file, BooleanSupplier cancelled) throws IOException {
        return file(file, cancelled, DEFAULT_BUFFER_BYTES);
    }

    public static Optional<String> file(Path file, BooleanSupplier cancelled, int bufferBytes) throws IOException {
        if (bufferBytes < 4 * 1024) throw new IllegalArgumentException("SHA-256 buffer must be at least 4 KiB");
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[bufferBytes];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) >= 0;) {
                if (cancelled != null && cancelled.getAsBoolean()) return Optional.empty();
                if (n > 0) digest.update(buffer, 0, n);
            }
        }
        return Optional.of(finish(digest));
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static void updateLengthPrefixedUtf8(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) ((bytes.length >>> 24) & 0xff));
        digest.update((byte) ((bytes.length >>> 16) & 0xff));
        digest.update((byte) ((bytes.length >>> 8) & 0xff));
        digest.update((byte) (bytes.length & 0xff));
        digest.update(bytes);
    }

    public static String finish(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
