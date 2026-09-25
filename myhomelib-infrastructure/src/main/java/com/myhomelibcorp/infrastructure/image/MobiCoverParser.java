package com.myhomelibcorp.infrastructure.image;

import com.myhomelibcorp.application.util.SafeFilePublication;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class MobiCoverParser {
    private static final int MAX_COVER_BYTES = 24 * 1024 * 1024;
    private static final int MAX_RECORDS = 200_000;

    public byte[] parse(Path path) throws IOException {
        Path safe = SafeFilePublication.requireSafeRegularFile(path, "MOBI cover source");
        try (FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long fileSize = channel.size();
            if (fileSize < 100) return null;

            ByteBuffer recordCount = readFully(channel, 76, 2);
            int records = Short.toUnsignedInt(recordCount.getShort());
            if (records <= 1 || records > MAX_RECORDS) return null;

            long recordTableEnd = 78L + (long) records * 8L;
            if (recordTableEnd > fileSize) return null;
            ByteBuffer table = readFully(channel, 78, records * 8);
            long[] offsets = new long[records];
            for (int i = 0; i < records; i++) {
                offsets[i] = Integer.toUnsignedLong(table.getInt());
                table.getInt(); // PalmDB record attributes/id
                if (offsets[i] < recordTableEnd || offsets[i] >= fileSize) return null;
                if (i > 0 && offsets[i] <= offsets[i - 1]) return null;
            }

            long first = offsets[0];
            long second = offsets[1];
            if (second <= first || second - first > 4 * 1024 * 1024L) return null;
            byte[] record0 = readBytes(channel, first, second - first);
            if (record0.length < 140 || !ascii(record0, 16, "MOBI")) return null;

            int mobi = 16;
            int headerLen = uint(record0, mobi + 4);
            int firstImageIndex = uint(record0, mobi + 92);
            int coverOffset = -1;
            if (headerLen >= 116 && mobi + (long) headerLen + 12 <= record0.length
                    && (uint(record0, mobi + 112) & 0x40) != 0) {
                int exth = mobi + headerLen;
                if (ascii(record0, exth, "EXTH")) {
                    int exthLen = uint(record0, exth + 4);
                    int count = uint(record0, exth + 8);
                    int pos = exth + 12;
                    int end = (int) Math.min(record0.length, exth + Math.max(12L, exthLen));
                    for (int i = 0; i < count && pos + 8 <= end && i < 10_000; i++) {
                        int type = uint(record0, pos);
                        int len = uint(record0, pos + 4);
                        if (len < 8 || pos + (long) len > end) break;
                        if (type == 201 && len >= 12) {
                            coverOffset = uint(record0, pos + 8);
                            break;
                        }
                        pos += len;
                    }
                }
            }

            int startRecord = firstImageIndex > 0 && firstImageIndex < records ? firstImageIndex : 1;
            if (coverOffset >= 0 && (long) startRecord + coverOffset < records) {
                byte[] cover = recordBytes(channel, fileSize, offsets, startRecord + coverOffset);
                if (isImage(cover)) return trimImage(cover);
            }
            for (int i = startRecord; i < Math.min(records, startRecord + 12); i++) {
                byte[] candidate = recordBytes(channel, fileSize, offsets, i);
                if (isImage(candidate)) return trimImage(candidate);
            }
            return null;
        }
    }

    private static byte[] recordBytes(FileChannel channel, long fileSize, long[] offsets, int index) throws IOException {
        if (index < 0 || index >= offsets.length) return null;
        long start = offsets[index];
        long end = index + 1 < offsets.length ? offsets[index + 1] : fileSize;
        long len = end - start;
        if (start < 0 || end > fileSize || len <= 0 || len > MAX_COVER_BYTES) return null;
        return readBytes(channel, start, len);
    }

    private static byte[] readBytes(FileChannel channel, long position, long length) throws IOException {
        if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("Invalid MOBI record length: " + length);
        ByteBuffer buffer = readFully(channel, position, (int) length);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private static ByteBuffer readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long cursor = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, cursor);
            if (read < 0) throw new EOFException("Unexpected end of MOBI file");
            if (read == 0) continue;
            cursor += read;
        }
        buffer.flip();
        return buffer;
    }

    private static boolean isImage(byte[] data) {
        if (data == null || data.length < 8) return false;
        int start = imageStart(data);
        if (start < 0) return false;
        return (data[start] & 0xff) == 0xff && (data[start + 1] & 0xff) == 0xd8
                || data[start] == (byte) 0x89 && data[start + 1] == 'P' && data[start + 2] == 'N' && data[start + 3] == 'G'
                || data[start] == 'G' && data[start + 1] == 'I' && data[start + 2] == 'F';
    }

    private static byte[] trimImage(byte[] data) {
        int start = imageStart(data);
        if (start <= 0) return data;
        return java.util.Arrays.copyOfRange(data, start, data.length);
    }

    private static int imageStart(byte[] data) {
        int limit = Math.min(data.length - 8, 512);
        for (int i = 0; i <= limit; i++) {
            if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xd8) return i;
            if (data[i] == (byte) 0x89 && data[i + 1] == 'P' && data[i + 2] == 'N' && data[i + 3] == 'G') return i;
            if (data[i] == 'G' && data[i + 1] == 'I' && data[i + 2] == 'F') return i;
        }
        return -1;
    }

    private static int uint(byte[] data, int pos) {
        if (pos < 0 || pos + 4 > data.length) return 0;
        long value = ((long) (data[pos] & 0xff) << 24) | ((long) (data[pos + 1] & 0xff) << 16)
                | ((long) (data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xffL);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static boolean ascii(byte[] data, int pos, String text) {
        if (pos < 0 || pos + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) if (data[pos + i] != (byte) text.charAt(i)) return false;
        return true;
    }
}
