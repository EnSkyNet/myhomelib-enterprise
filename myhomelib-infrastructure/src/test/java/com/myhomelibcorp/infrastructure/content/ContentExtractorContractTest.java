package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ContentExtractionResult;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ContentExtractionSource;
import com.myhomelibcorp.application.content.ContentExtractionStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentExtractorContractTest {

    private final ContentExtractionService service = new ContentExtractionService(List.of(
            new EpubContentExtractor(), new Fb2ContentExtractor(), new TxtContentExtractor()));

    @Test
    void extractsFb2ChaptersTextAndAnchorsInDocumentOrder() {
        String fb2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <body>
                    <section><title><p>Розділ один</p></title><p>Перший абзац.</p><p>Другий абзац.</p></section>
                    <section><title><p>Розділ два</p></title><p>Третій абзац.</p></section>
                  </body>
                </FictionBook>
                """;

        ContentExtractionResult result = service.extract(
                new ContentExtractionRequest(source("book.fb2", fb2.getBytes(StandardCharsets.UTF_8)), "fb2"),
                ContentExtractionContext.none());

        assertEquals(ContentExtractionStatus.SUCCESS, result.status());
        assertEquals("fb2-content", result.extractorId());
        assertEquals(2, result.content().chapters().size());
        assertEquals("Розділ один", result.content().chapters().get(0).title());
        assertEquals("Розділ два", result.content().chapters().get(1).title());
        assertTrue(result.content().text().contains("Перший абзац."));
        assertTrue(result.content().text().contains("Третій абзац."));
        assertEquals(5, result.content().anchors().size());
        assertEquals(0L, result.content().chapters().get(0).startOffset());
        assertTrue(result.content().chapters().get(1).startOffset() > result.content().chapters().get(0).endOffset());
    }

    @Test
    void extractsTxtWithUnicodeAndStableParagraphAnchors() {
        byte[] bytes = ("Перший рядок\nпродовження\n\nДругий абзац — UTF-8 ✓\n").getBytes(StandardCharsets.UTF_8);

        ContentExtractionResult result = service.extract(
                ContentExtractionRequest.of(source("notes.txt", bytes)), ContentExtractionContext.none());

        assertEquals(ContentExtractionStatus.SUCCESS, result.status());
        assertEquals("txt-content", result.extractorId());
        assertEquals("Перший рядок продовження\nДругий абзац — UTF-8 ✓", result.content().text());
        assertEquals(1, result.content().chapters().size());
        assertEquals(2, result.content().anchors().size());
    }

    @Test
    void extractsEpubInSpineOrderAndIgnoresScriptAndStyle() throws IOException {
        byte[] epub = epub(
                "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest>
                        <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>
                    """,
                List.of(
                        entry("OEBPS/chapter1.xhtml", """
                            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fallback one</title><style>hidden-style</style></head>
                            <body><h1>Chapter One</h1><p>Alpha text.</p><script>hidden-script</script></body></html>
                            """),
                        entry("OEBPS/chapter2.xhtml", """
                            <html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Chapter Two</h2><p>Beta text.</p></body></html>
                            """)));

        ContentExtractionResult result = service.extract(
                new ContentExtractionRequest(source("book.epub", epub), "epub"), ContentExtractionContext.none());

        assertEquals(ContentExtractionStatus.SUCCESS, result.status());
        assertEquals(2, result.content().chapters().size());
        assertEquals("Chapter One", result.content().chapters().get(0).title());
        assertEquals("Chapter Two", result.content().chapters().get(1).title());
        assertTrue(result.content().text().indexOf("Alpha text.") < result.content().text().indexOf("Beta text."));
        assertFalse(result.content().text().contains("hidden-script"));
        assertFalse(result.content().text().contains("hidden-style"));
    }

    @Test
    void rejectsUnsafeEpubHrefInsteadOfEscapingArchiveRoot() throws IOException {
        byte[] epub = epub(
                "content.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest><item id="evil" href="../../evil.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="evil"/></spine>
                    </package>
                    """, List.of());

        ContentExtractionResult result = service.extract(
                new ContentExtractionRequest(source("unsafe.epub", epub), "epub"), ContentExtractionContext.none());

        assertEquals(ContentExtractionStatus.FAILED, result.status());
        assertTrue(result.message().toLowerCase().contains("escapes archive root"));
    }

    @Test
    void neverResolvesFb2ExternalEntityInput() {
        String fb2 = """
                <?xml version="1.0"?>
                <!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <FictionBook><body><section><p>&xxe;</p></section></body></FictionBook>
                """;

        ContentExtractionResult result = service.extract(
                new ContentExtractionRequest(source("unsafe.fb2", fb2.getBytes(StandardCharsets.UTF_8)), "fb2"),
                ContentExtractionContext.none());

        assertTrue(result.status() == ContentExtractionStatus.FAILED
                || !result.content().text().contains("root:"));
    }

    @Test
    void cancellationIsReportedAsTypedResult() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        ContentExtractionContext context = ContentExtractionContext.create(cancelled, ignored -> { });
        ContentExtractionResult result = service.extract(
                ContentExtractionRequest.of(source("book.txt", "text".getBytes(StandardCharsets.UTF_8))), context);

        assertEquals(ContentExtractionStatus.CANCELLED, result.status());
        assertEquals("txt-content", result.extractorId());
    }

    private static ContentExtractionSource source(String name, byte[] bytes) {
        byte[] immutable = bytes.clone();
        return new ContentExtractionSource() {
            @Override public String id() { return "memory:" + name; }
            @Override public String name() { return name; }
            @Override public InputStream openStream() { return new ByteArrayInputStream(immutable); }
            @Override public OptionalLong size() { return OptionalLong.of(immutable.length); }
        };
    }

    private static byte[] epub(String opfPath, String opf, List<NamedEntry> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="%s" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """.formatted(opfPath));
            put(zip, opfPath, opf);
            for (NamedEntry entry : entries) put(zip, entry.name(), entry.content());
        }
        return bytes.toByteArray();
    }

    private static NamedEntry entry(String name, String content) {
        return new NamedEntry(name, content);
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private record NamedEntry(String name, String content) { }
}
