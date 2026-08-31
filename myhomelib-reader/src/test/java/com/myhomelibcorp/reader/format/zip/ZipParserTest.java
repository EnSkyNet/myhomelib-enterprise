package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static void add(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
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
