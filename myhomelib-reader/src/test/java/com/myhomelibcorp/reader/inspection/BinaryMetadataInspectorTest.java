package com.myhomelibcorp.reader.inspection;

import com.myhomelibcorp.reader.api.FileBookSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryMetadataInspectorTest {
    @TempDir Path temp;

    @Test
    void readsBasicPdfInfoWithoutExternalPdfLibrary() throws Exception {
        Path file = temp.resolve("sample.pdf");
        Files.writeString(file, "%PDF-1.4\n1 0 obj << /Title (Demo PDF) /Author (Ada Lovelace) /Subject (Notes) /Producer (Unit Test) >> endobj\n%%EOF",
                StandardCharsets.ISO_8859_1);
        DocumentInspection result = BinaryMetadataInspector.inspect(new FileBookSource(file));
        assertThat(result.parsed()).isTrue();
        assertThat(result.format()).isEqualTo("PDF");
        assertThat(result.title()).isEqualTo("Demo PDF");
        assertThat(result.authors()).containsExactly("Ada Lovelace");
        assertThat(result.annotation()).isEqualTo("Notes");
    }

    @Test
    void recognizesDjvuAndFallsBackGracefully() throws Exception {
        Path file = temp.resolve("sample.djvu");
        byte[] bytes = new byte[32];
        System.arraycopy("AT&TFORM".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 8);
        Files.write(file, bytes);
        DocumentInspection result = BinaryMetadataInspector.inspect(new FileBookSource(file));
        assertThat(result.parsed()).isTrue();
        assertThat(result.format()).isEqualTo("DJVU");
        assertThat(result.warning()).contains("базові метадані");
    }

    @Test
    void readsMobiExthMetadata() throws Exception {
        Path file = temp.resolve("sample.mobi");
        byte[] data = new byte[2048];
        putU16(data, 76, 1);
        putU32(data, 78, 86);
        int r0 = 86;
        putAscii(data, r0 + 16, "MOBI");
        putU32(data, r0 + 20, 232); // MOBI header length
        putU32(data, r0 + 28, 65001); // UTF-8
        putU32(data, r0 + 16 + 112, 0x40); // EXTH present
        int exth = r0 + 16 + 232;
        putAscii(data, exth, "EXTH");
        byte[][] records = {
                exthRecord(503, "MOBI Demo"),
                exthRecord(100, "Test Author"),
                exthRecord(101, "Test Publisher"),
                exthRecord(103, "<p>Annotation</p>"),
                exthRecord(104, "9780000000001"),
                exthRecord(106, "2026-08-25"),
                exthRecord(524, "en")
        };
        int length = 12;
        for (byte[] record : records) length += record.length;
        putU32(data, exth + 4, length);
        putU32(data, exth + 8, records.length);
        int pos = exth + 12;
        for (byte[] record : records) {
            System.arraycopy(record, 0, data, pos, record.length);
            pos += record.length;
        }
        Files.write(file, data);

        DocumentInspection result = BinaryMetadataInspector.inspect(new FileBookSource(file));
        assertThat(result.parsed()).isTrue();
        assertThat(result.title()).isEqualTo("MOBI Demo");
        assertThat(result.authors()).containsExactly("Test Author");
        assertThat(result.publisher()).isEqualTo("Test Publisher");
        assertThat(result.annotation()).isEqualTo("Annotation");
        assertThat(result.isbn()).isEqualTo("9780000000001");
        assertThat(result.year()).isEqualTo("2026-08-25");
        assertThat(result.language()).isEqualTo("en");
    }

    private static byte[] exthRecord(int type, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(type).putInt(8 + text.length).put(text);
        return b.array();
    }
    private static void putAscii(byte[] data, int pos, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, pos, bytes.length);
    }
    private static void putU16(byte[] data, int pos, int value) {
        data[pos] = (byte) (value >>> 8); data[pos + 1] = (byte) value;
    }
    private static void putU32(byte[] data, int pos, int value) {
        ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }
}
