package com.myhomelibcorp.infrastructure.image;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

@Component
public class MobiCoverParser {
    private static final int MAX_COVER_BYTES = 24 * 1024 * 1024;

    public byte[] parse(Path path) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            if (raf.length() < 100) return null;
            raf.seek(76);
            int records = raf.readUnsignedShort();
            if (records <= 1 || records > 200_000) return null;
            long[] offsets = new long[records];
            raf.seek(78);
            for (int i = 0; i < records; i++) {
                offsets[i] = Integer.toUnsignedLong(raf.readInt());
                raf.skipBytes(4);
            }
            long first = offsets[0];
            long second = offsets[1];
            if (second <= first || second - first > 4 * 1024 * 1024L) return null;
            byte[] record0 = new byte[(int) (second - first)];
            raf.seek(first);
            raf.readFully(record0);
            if (record0.length < 140 || !ascii(record0, 16, "MOBI")) return null;
            int mobi = 16;
            int headerLen = uint(record0, mobi + 4);
            int firstImageIndex = uint(record0, mobi + 92);
            int coverOffset = -1;
            if (headerLen >= 116 && mobi + headerLen + 12 <= record0.length && (uint(record0, mobi + 112) & 0x40) != 0) {
                int exth = mobi + headerLen;
                if (ascii(record0, exth, "EXTH")) {
                    int exthLen = uint(record0, exth + 4);
                    int count = uint(record0, exth + 8);
                    int pos = exth + 12;
                    int end = Math.min(record0.length, exth + Math.max(12, exthLen));
                    for (int i = 0; i < count && pos + 8 <= end && i < 10_000; i++) {
                        int type = uint(record0, pos);
                        int len = uint(record0, pos + 4);
                        if (len < 8 || pos + len > end) break;
                        if (type == 201 && len >= 12) {
                            coverOffset = uint(record0, pos + 8);
                            break;
                        }
                        pos += len;
                    }
                }
            }
            int startRecord = firstImageIndex > 0 && firstImageIndex < records ? firstImageIndex : 1;
            if (coverOffset >= 0 && startRecord + coverOffset < records) {
                byte[] cover = recordBytes(raf, offsets, startRecord + coverOffset);
                if (isImage(cover)) return trimImage(cover);
            }
            for (int i = startRecord; i < Math.min(records, startRecord + 12); i++) {
                byte[] candidate = recordBytes(raf, offsets, i);
                if (isImage(candidate)) return trimImage(candidate);
            }
            return null;
        }
    }

    private static byte[] recordBytes(RandomAccessFile raf, long[] offsets, int index) throws IOException {
        if (index < 0 || index >= offsets.length) return null;
        long start = offsets[index];
        long end = index + 1 < offsets.length ? offsets[index + 1] : raf.length();
        long len = end - start;
        if (len <= 0 || len > MAX_COVER_BYTES) return null;
        byte[] data = new byte[(int) len];
        raf.seek(start);
        raf.readFully(data);
        return data;
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
