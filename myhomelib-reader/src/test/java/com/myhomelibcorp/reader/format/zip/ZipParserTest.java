package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ZipParserTest {
    @TempDir Path tempDir;

    @Test
    void mergesSeveralBooksAndBuildsBookToChapterToc() throws Exception {
        Path zip = tempDir.resolve("anthology.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "one.fb2", fb2("Книга один", "Розділ A", "Текст першої книги."));
            add(out, "two.fb2", fb2("Книга два", "Розділ B", "Текст другої книги."));
        }

        ReaderDocument document = new ZipParser().parse(new FileBookSource(zip), ParseOptions.withoutImages());

        assertThat(document.text().getFullText()).contains("Текст першої книги.", "Текст другої книги.");
        assertThat(document.toc().entries()).hasSize(2);
        assertThat(document.toc().entries().get(0).title()).isEqualTo("Книга один");
        assertThat(document.toc().entries().get(0).children()).extracting(e -> e.title()).contains("Розділ A");
        assertThat(document.toc().entries().get(1).title()).isEqualTo("Книга два");
        assertThat(document.toc().entries().get(1).children()).extracting(e -> e.title()).contains("Розділ B");
        assertThat(document.toc().entries().get(1).textOffset()).isGreaterThan(document.toc().entries().get(0).textOffset());
    }

    @Test
    void parsesMobiEntryWithMobiParserInsteadOfTreatingItAsFb2() throws Exception {
        Path zip = tempDir.resolve("mobi-inside.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, "book.mobi", mobi("ZIP MOBI", "<h1>Chapter</h1><p>Nested MOBI text</p>"));
        }

        ReaderDocument document = new ZipParser().parse(new FileBookSource(zip), ParseOptions.withoutImages());

        assertThat(document.metadata().title()).isEqualTo("ZIP MOBI");
        assertThat(document.text().getFullText()).contains("Chapter", "Nested MOBI text");
    }

    private static void add(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static void add(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static byte[] mobi(String title, String html) {
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = html.getBytes(StandardCharsets.UTF_8);
        int record0Length = 256;
        int directoryEnd = 78 + 2 * 8;
        int record0Offset = directoryEnd;
        int textOffset = record0Offset + record0Length;
        byte[] file = new byte[textOffset + textBytes.length];
        ByteBuffer all = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        all.putShort(76, (short) 2);
        all.putInt(78, record0Offset);
        all.putInt(86, textOffset);

        ByteBuffer record0 = ByteBuffer.wrap(file, record0Offset, record0Length).slice().order(ByteOrder.BIG_ENDIAN);
        record0.putShort(0, (short) 1);
        record0.putInt(4, textBytes.length);
        record0.putShort(8, (short) 1);
        record0.putShort(10, (short) 4096);
        record0.putShort(12, (short) 0);
        record0.position(16);
        record0.put("MOBI".getBytes(StandardCharsets.US_ASCII));
        record0.putInt(20, 116);
        record0.putInt(24, 2);
        record0.putInt(28, 65001);
        int fullNameOffset = 140;
        record0.putInt(84, fullNameOffset);
        record0.putInt(88, titleBytes.length);
        record0.position(fullNameOffset);
        record0.put(titleBytes);
        System.arraycopy(textBytes, 0, file, textOffset, textBytes.length);
        return file;
    }

    private static String fb2(String title, String chapter, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>%s</book-title><lang>uk</lang></title-info></description>
                  <body><section><title><p>%s</p></title><p>%s</p></section></body>
                </FictionBook>
                """.formatted(title, chapter, body);
    }
}
