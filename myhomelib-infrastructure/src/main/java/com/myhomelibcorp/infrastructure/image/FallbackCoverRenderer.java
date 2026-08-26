package com.myhomelibcorp.infrastructure.image;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Headless-safe neutral PNG fallback. It intentionally avoids AWT/JavaFX so
 * cover extraction also works in tests, server-side OPDS and headless builds.
 */
@Component
public class FallbackCoverRenderer {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 450;

    public byte[] render(String format, String title) {
        try {
            byte[] raw = new byte[(WIDTH * 3 + 1) * HEIGHT];
            int seed = Math.abs(((format == null ? "" : format) + "|" + (title == null ? "" : title)).hashCode());
            int base = 220 + seed % 18;
            int accent = 72 + seed % 70;
            int p = 0;
            for (int y = 0; y < HEIGHT; y++) {
                raw[p++] = 0; // PNG filter type: None
                for (int x = 0; x < WIDTH; x++) {
                    boolean band = y > 88 && y < 104 || y > HEIGHT - 82 && y < HEIGHT - 72;
                    int value = band ? accent : Math.max(190, base - (y * 16 / HEIGHT));
                    raw[p++] = (byte) value;
                    raw[p++] = (byte) value;
                    raw[p++] = (byte) value;
                }
            }

            Deflater deflater = new Deflater(6);
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                if (n <= 0) break;
                compressed.write(buffer, 0, n);
            }
            deflater.end();

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(png);
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(ihdr);
            h.writeInt(WIDTH); h.writeInt(HEIGHT); h.writeByte(8); h.writeByte(2);
            h.writeByte(0); h.writeByte(0); h.writeByte(0);
            writeChunk(out, "IHDR", ihdr.toByteArray());
            writeChunk(out, "IDAT", compressed.toByteArray());
            writeChunk(out, "IEND", new byte[0]);
            out.flush();
            return png.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws Exception {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
