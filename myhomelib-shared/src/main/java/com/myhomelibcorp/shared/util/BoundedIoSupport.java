package com.myhomelibcorp.shared.util;

import java.io.IOException;
import java.io.InputStream;

/** Small bounded-read primitives for metadata/config inputs that must never consume unbounded heap. */
public final class BoundedIoSupport {
    private BoundedIoSupport() { }

    public static byte[] readFully(InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IllegalArgumentException("input cannot be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        // Avoid maxBytes + 1 integer overflow; probe only after the bounded read.
        byte[] bytes = input.readNBytes(maxBytes);
        if (bytes.length == maxBytes && input.read() != -1) {
            throw new IOException("Input exceeds maximum allowed size: " + maxBytes + " bytes");
        }
        return bytes;
    }

    public static byte[] readPrefix(InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IllegalArgumentException("input cannot be null");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        return input.readNBytes(maxBytes);
    }
}
