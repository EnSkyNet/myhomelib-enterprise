package com.myhomelibcorp.shared.archive;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reads the first ZIP local-file header without inflating the entry.
 *
 * <p>This is intentionally used for EPUB's OCF ordering rule. {@link java.util.zip.ZipInputStream}
 * rejects some valid producer output where the uncompressed {@code mimetype} entry carries the
 * ZIP data-descriptor flag, even though the central directory is valid and {@link java.util.zip.ZipFile}
 * can read the archive. OCF only requires the first local entry to be {@code mimetype}, STORED and
 * without an extra field; those facts are available directly in the local header.</p>
 */
public final class ZipLocalHeaderInspector {
    private static final int LOCAL_HEADER_BYTES = 30;
    private static final int LOCAL_FILE_SIGNATURE = 0x04034b50;
    private static final int MAX_FIRST_NAME_BYTES = 16 * 1024;

    private ZipLocalHeaderInspector() { }

    public static FirstEntry inspectFirstEntry(Path source, String role) throws IOException {
        Objects.requireNonNull(source, "source");
        String effectiveRole = role == null || role.isBlank() ? "ZIP archive" : role;
        Path safe = AtomicFileSupport.requireSafeRegularFile(source, effectiveRole);
        try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer header = ByteBuffer.allocate(LOCAL_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, header);
            header.flip();
            if (header.getInt() != LOCAL_FILE_SIGNATURE) {
                throw new IOException("ZIP local-file header signature is missing");
            }
            header.position(6);
            int generalPurposeFlags = Short.toUnsignedInt(header.getShort());
            int method = Short.toUnsignedInt(header.getShort());
            header.position(26);
            int nameLength = Short.toUnsignedInt(header.getShort());
            int extraLength = Short.toUnsignedInt(header.getShort());
            if (nameLength <= 0 || nameLength > MAX_FIRST_NAME_BYTES) {
                throw new IOException("ZIP first entry name length is invalid: " + nameLength);
            }
            byte[] rawName = new byte[nameLength];
            readFully(channel, ByteBuffer.wrap(rawName));
            // EPUB's required first name is ASCII, so byte-exact ASCII decoding is sufficient and
            // avoids depending on the archive's optional legacy filename encoding flags here.
            String name = new String(rawName, StandardCharsets.US_ASCII);
            return new FirstEntry(name, method, extraLength, generalPurposeFlags, rawName);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new EOFException("ZIP file is truncated");
        }
    }

    public record FirstEntry(String name, int method, int extraLength, int generalPurposeFlags, byte[] rawName) {
        public FirstEntry {
            rawName = rawName == null ? new byte[0] : Arrays.copyOf(rawName, rawName.length);
        }

        @Override public byte[] rawName() { return Arrays.copyOf(rawName, rawName.length); }
    }
}
