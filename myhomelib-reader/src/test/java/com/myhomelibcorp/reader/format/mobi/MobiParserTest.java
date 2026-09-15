package com.myhomelibcorp.reader.format.mobi;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobiParserTest {

    @Test
    void parsesUncompressedDrmFreeMobiAndBuildsToc() throws Exception {
        byte[] mobi = mobi("Demo MOBI", "<h1>Розділ 1</h1><p>Привіт MOBI</p>", 1, 0);
        var document = new MobiParser().parse(source("demo.mobi", "mobi", mobi), ParseOptions.withoutImages());

        assertThat(document.metadata().title()).isEqualTo("Demo MOBI");
        assertThat(document.text().getFullText()).contains("Розділ 1", "Привіт MOBI");
        assertThat(document.toc().entries()).isNotEmpty();
        assertThat(document.chapters()).isNotEmpty();
    }

    @Test
    void rejectsDrmProtectedContainerWithClearMessage() {
        byte[] mobi = mobi("Protected", "<p>secret</p>", 1, 1);
        assertThatThrownBy(() -> new MobiParser().parse(source("protected.azw", "azw", mobi), ParseOptions.minimal()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("DRM");
    }


    @Test
    void parsesPalmDocCompressedLiteralText() throws Exception {
        byte[] mobi = mobi("PalmDOC", "<h1>Chapter</h1><p>Hello MOBI</p>", 2, 0);
        var document = new MobiParser().parse(source("palmdoc.mobi", "mobi", mobi), ParseOptions.withoutImages());
        assertThat(document.text().getFullText()).contains("Chapter", "Hello MOBI");
    }

    @Test
    void rejectsHuffCdicWithConversionHint() {
        byte[] mobi = mobi("HUFF", "<p>compressed</p>", 17480, 0);
        assertThatThrownBy(() -> new MobiParser().parse(source("huff.azw3", "azw3", mobi), ParseOptions.minimal()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HUFF/CDIC")
                .hasMessageContaining("EPUB/FB2");
    }

    @Test
    void standardRegistryRecognizesMobiPrcAzwAndAzw3() {
        var registry = DefaultBookFormatRegistry.standard();
        assertThat(registry.findByExtension("mobi")).isPresent();
        assertThat(registry.findByExtension("prc")).isPresent();
        assertThat(registry.findByExtension("azw")).isPresent();
        assertThat(registry.findByExtension("azw3")).isPresent();
    }

    private static BookSource source(String name, String extension, byte[] bytes) {
        return new BookSource() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
            @Override public String name() { return name; }
            @Override public String extension() { return extension; }
            @Override public String id() { return "test:" + name; }
        };
    }

    /** Builds the smallest PalmDB/MOBI container needed by the Reader parser. */
    private static byte[] mobi(String title, String html, int compression, int encryption) {
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = html.getBytes(StandardCharsets.UTF_8);
        int record0Length = 256;
        int directoryEnd = 78 + 2 * 8;
        int record0Offset = directoryEnd;
        int textOffset = record0Offset + record0Length;
        byte[] file = new byte[textOffset + textBytes.length];
        ByteBuffer all = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);

        // Palm Database header + two record directory entries.
        all.putShort(76, (short) 2);
        all.putInt(78, record0Offset);
        all.putInt(86, textOffset);

        ByteBuffer record0 = ByteBuffer.wrap(file, record0Offset, record0Length).slice().order(ByteOrder.BIG_ENDIAN);
        // PalmDOC header.
        record0.putShort(0, (short) compression);
        record0.putInt(4, textBytes.length);
        record0.putShort(8, (short) 1); // one text record
        record0.putShort(10, (short) 4096);
        record0.putShort(12, (short) encryption);

        // MOBI header begins at offset 16 inside record 0.
        record0.position(16);
        record0.put("MOBI".getBytes(StandardCharsets.US_ASCII));
        record0.putInt(20, 116);       // MOBI header length
        record0.putInt(24, 2);         // Mobipocket book
        record0.putInt(28, 65001);     // UTF-8
        int fullNameOffset = 140;       // absolute offset inside record 0
        record0.putInt(84, fullNameOffset);
        record0.putInt(88, titleBytes.length);
        record0.position(fullNameOffset);
        record0.put(titleBytes);

        System.arraycopy(textBytes, 0, file, textOffset, textBytes.length);
        return file;
    }
}
