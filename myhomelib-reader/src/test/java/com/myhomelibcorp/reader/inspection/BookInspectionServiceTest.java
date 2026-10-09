package com.myhomelibcorp.reader.inspection;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.FileBookSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BookInspectionServiceTest {
    @TempDir Path temp;

    @Test
    void fb2InspectionProvidesSourceLanguageTocMetricsAndLazyImages() throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                  <description><title-info>
                    <genre>prose</genre><author><first-name>Ada</first-name><last-name>Writer</last-name></author>
                    <book-title>Inspection Demo</book-title><lang>uk</lang><src-lang>en</src-lang>
                    <annotation><p>Catalog fallback annotation.</p></annotation>
                  </title-info></description>
                  <body><section><title><p>Chapter One</p></title><p>One two three four five.</p><image l:href="#img1"/></section></body>
                  <binary id="img1" content-type="image/png">%s</binary>
                </FictionBook>
                """.formatted(Base64.getEncoder().encodeToString(tinyPng));
        Path file = temp.resolve("demo.fb2");
        Files.writeString(file, fb2, StandardCharsets.UTF_8);

        try (DocumentInspectionSession session = new BookInspectionService().inspect(new FileBookSource(file))) {
            DocumentInspection result = session.inspection();
            assertThat(result.parsed()).isTrue();
            assertThat(result.sourceLanguage()).isEqualTo("en");
            assertThat(result.wordCount()).isGreaterThanOrEqualTo(5);
            assertThat(result.characterCount()).isGreaterThan(0);
            assertThat(result.tocPreview()).extracting(TocPreviewEntry::title).contains("Chapter One");
            assertThat(result.images()).isNotEmpty();
            assertThat(session.openImage(result.images().getFirst().id())).isPresent();
        }
    }
    @Test
    void fb2InspectionDefersBinaryPayloadUntilImageIsOpened() throws Exception {
        byte[] tinyPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=");
        byte[] fb2 = ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
                  <description><title-info><book-title>Lazy image</book-title><lang>uk</lang><src-lang>en</src-lang></title-info></description>
                  <body><section><p>One two three.</p><image l:href="#img1"/></section></body>
                  <binary id="img1" content-type="image/png">%s</binary>
                </FictionBook>
                """).formatted(Base64.getEncoder().encodeToString(tinyPng)).getBytes(StandardCharsets.UTF_8);
        AtomicInteger opens = new AtomicInteger();
        BookSource source = new BookSource() {
            @Override public InputStream openStream() { opens.incrementAndGet(); return new ByteArrayInputStream(fb2); }
            @Override public OptionalLong size() { return OptionalLong.of(fb2.length); }
            @Override public String name() { return "lazy.fb2"; }
            @Override public String extension() { return "fb2"; }
            @Override public String id() { return "lazy"; }
        };

        try (DocumentInspectionSession session = new BookInspectionService().inspect(source)) {
            assertThat(session.inspection().images()).hasSize(1);
            // Initial passive inspection parses text/metadata and source language, but does not
            // decode the binary image payload.
            assertThat(opens).hasValue(2);
            try (InputStream image = session.openImage("img1").orElseThrow()) {
                assertThat(image.readAllBytes()).isEqualTo(tinyPng);
            }
            assertThat(opens).hasValue(3);
            try (InputStream image = session.openImage("img1").orElseThrow()) {
                assertThat(image.readAllBytes()).isEqualTo(tinyPng);
            }
            assertThat(opens).hasValue(3);
        }
    }

    @Test
    void epubInspectionFallsBackToOpfMetadataWhenSpineXhtmlCannotBeFullyParsed() throws Exception {
        Path epub = temp.resolve("metadata-fallback.epub");
        try (OutputStream raw = Files.newOutputStream(epub); ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "META-INF/container.xml",
                    "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
            put(zip, "OPS/book.opf", """
                    <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title>Fallback EPUB</dc:title>
                      <dc:creator>Test Author</dc:creator>
                      <dc:language>uk</dc:language>
                      <dc:publisher>Test Publisher</dc:publisher>
                      <dc:date>2026-01-01</dc:date>
                      <dc:description>EPUB metadata annotation.</dc:description>
                    </metadata><manifest>
                      <item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="c"/></spine></package>
                    """);
            // Deliberately malformed XHTML: full Reader parse must fail, while OPF metadata is valid.
            put(zip, "OPS/chapter.xhtml", "<html><body><p>broken</body></html>");
        }

        try (DocumentInspectionSession session = new BookInspectionService().inspect(new FileBookSource(epub))) {
            DocumentInspection result = session.inspection();
            assertThat(result.parsed()).isTrue();
            assertThat(result.title()).isEqualTo("Fallback EPUB");
            assertThat(result.authors()).containsExactly("Test Author");
            assertThat(result.language()).isEqualTo("uk");
            assertThat(result.publisher()).isEqualTo("Test Publisher");
            assertThat(result.year()).isEqualTo("2026");
            assertThat(result.annotation()).isEqualTo("EPUB metadata annotation.");
            assertThat(result.warning()).contains("показано метадані");
            assertThat(result.wordCount()).isZero();
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

}
