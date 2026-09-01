package com.myhomelibcorp.reader.performance;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/** Stage 20 guardrail: large documents must stay streaming/bounded and finish in a practical time. */
class ReaderLargeDocumentPerformanceTest {
    @TempDir Path temp;

    @Test
    void parsesLargeFb2WithoutBuildingADom() {
        String paragraph = "<p>Великий тестовий абзац для перевірки потокового FB2 reader та стабільності layout source offsets.</p>";
        String fb2 = "<?xml version=\"1.0\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">"
                + "<description><title-info><book-title>Large</book-title><lang>uk</lang></title-info></description><body><section>"
                + paragraph.repeat(18_000) + "</section></body></FictionBook>";
        var source = new MemorySource("large.fb2", fb2.getBytes(StandardCharsets.UTF_8));
        var doc = assertTimeout(Duration.ofSeconds(15), () -> new Fb2StreamingParser().parse(source, ParseOptions.withoutImages()));
        assertThat(doc.totalTextLength()).isGreaterThan(1_000_000);
        assertThat(doc.text().getParagraphCount()).isGreaterThan(10_000);
    }

    @Test
    void parsesLargeEpubSpineWithinGuardrail() throws Exception {
        Path epub = temp.resolve("large.epub");
        String paragraph = "<p>Large EPUB paragraph with enough content to exercise streaming XHTML parsing and paragraph indexing.</p>";

        // Створюємо ZIP з мінімальним рівнем стиснення (1)
        // Це запобігає спрацюванню zip-bomb detection
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            // Встановлюємо мінімальний рівень стиснення для всього ZIP
            zip.setLevel(1);

            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml",
                    "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                            "<rootfiles><rootfile full-path=\"OPS/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(zip, "OPS/book.opf",
                    "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
                            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Large EPUB</dc:title>" +
                            "<dc:language>en</dc:language></metadata><manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                            "</manifest><spine><itemref idref=\"c\"/></spine></package>");
            put(zip, "OPS/c.xhtml",
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + paragraph.repeat(16_000) + "</body></html>");
        }
        var doc = assertTimeout(Duration.ofSeconds(15), () -> new EpubParser().parse(new FileBookSource(epub), ParseOptions.withoutImages()));
        assertThat(doc.totalTextLength()).isGreaterThan(1_000_000);
        assertThat(doc.text().getParagraphCount()).isGreaterThan(10_000);
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private record MemorySource(String name, byte[] bytes) implements BookSource {
        @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
        @Override public String extension() { int dot=name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1); }
        @Override public String id() { return "large-fixture"; }
    }
}