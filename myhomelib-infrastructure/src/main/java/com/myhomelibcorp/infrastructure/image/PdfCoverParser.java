package com.myhomelibcorp.infrastructure.image;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PdfCoverParser {
    private static final int MAX_SCAN_BYTES = 24 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /** Best-effort extraction of the first DCT/JPEG page image without rendering PDF. */
    public byte[] parse(Path path) throws IOException {
        byte[] data;
        try (InputStream in = Files.newInputStream(path)) {
            data = in.readNBytes(MAX_SCAN_BYTES);
        }
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') return null;
        byte[] subtype = "/Subtype /Image".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] dct = "/DCTDecode".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] stream = "stream".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int pos = 0;
        while ((pos = indexOf(data, subtype, pos)) >= 0) {
            int searchEnd = Math.min(data.length, pos + 4096);
            int dctPos = indexOf(data, dct, pos);
            if (dctPos < 0 || dctPos > searchEnd) { pos += subtype.length; continue; }
            int streamPos = indexOf(data, stream, dctPos);
            if (streamPos < 0 || streamPos > searchEnd + 1024) { pos += subtype.length; continue; }
            int jpegStart = findJpegStart(data, streamPos + stream.length, Math.min(data.length, streamPos + 2048));
            if (jpegStart < 0) { pos += subtype.length; continue; }
            int jpegEnd = findJpegEnd(data, jpegStart + 2, Math.min(data.length, jpegStart + MAX_IMAGE_BYTES));
            if (jpegEnd > jpegStart) return java.util.Arrays.copyOfRange(data, jpegStart, jpegEnd + 2);
            pos += subtype.length;
        }
        return null;
    }

    private static int findJpegStart(byte[] data, int start, int end) {
        for (int i = Math.max(0, start); i + 1 < end; i++) {
            if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xd8) return i;
        }
        return -1;
    }

    private static int findJpegEnd(byte[] data, int start, int end) {
        for (int i = Math.max(0, start); i + 1 < end; i++) {
            if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xd9) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] data, byte[] needle, int start) {
        outer: for (int i = Math.max(0, start); i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
