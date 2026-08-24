package com.myhomelibcorp.reader.format.txt;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ReaderDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TxtParserTest {
    @Test
    void decodesWindows1251AndBuildsSingleChapter() throws Exception {
        byte[] bytes = "Назва книги\r\nПерший абзац\r\nДругий абзац".getBytes(Charset.forName("windows-1251"));
        BookSource source = source("legacy.txt", bytes);

        ReaderDocument doc = new TxtParser().parse(source);

        assertThat(doc.metadata().title()).isEqualTo("Назва книги");
        assertThat(doc.text().getFullText()).contains("Перший абзац", "Другий абзац");
        assertThat(doc.chapters()).hasSize(1);
        assertThat(doc.toc().entries()).hasSize(1);
    }

    @Test
    void honorsUtf8Bom() throws Exception {
        byte[] body = "Український текст\nрядок".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xef; bytes[1] = (byte) 0xbb; bytes[2] = (byte) 0xbf;
        System.arraycopy(body, 0, bytes, 3, body.length);
        ReaderDocument doc = new TxtParser().parse(source("bom.txt", bytes));
        assertThat(doc.text().getFullText()).startsWith("Український текст");
    }

    private BookSource source(String name, byte[] bytes) {
        return new BookSource() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
            @Override public String name() { return name; }
            @Override public String extension() { return "txt"; }
            @Override public String id() { return "test:" + name; }
        };
    }
}
