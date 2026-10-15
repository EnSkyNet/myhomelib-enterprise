package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContentExtractionResourceRegressionTest {
    private final ContentExtractionService service = new ContentExtractionService(List.of(
            new Fb2ContentExtractor(), new TxtContentExtractor()));

    @Test
    void rejectsOversizedParagraphsBeforeReadingWholeInput() {
        for (String format : List.of("txt", "fb2")) {
            byte[] bytes = (format.equals("fb2")
                    ? "<FictionBook><body><section><p>" + "x".repeat(1_000_000) + "</p></section></body></FictionBook>"
                    : "x".repeat(1_000_000)).getBytes(StandardCharsets.UTF_8);
            AtomicInteger consumed = new AtomicInteger();
            var bounded = new ContentExtractionService(List.of(new TxtContentExtractor(32), new Fb2ContentExtractor(32)));
            var result = bounded.extract(ContentExtractionRequest.of(source(format, bytes, consumed)), ContentExtractionContext.none());
            assertEquals(ContentExtractionStatus.FAILED, result.status());
            assertTrue(result.message().contains("safety limit"), result.message());
            assertTrue(consumed.get() < bytes.length / 2, "Stop near the limit, not at EOF");
        }
    }
    @Test
    void cancelsTxtBeforeEndOfUnbrokenLine() {
        byte[] bytes = "x".repeat(1_000_000).getBytes(StandardCharsets.UTF_8);
        AtomicInteger consumed = new AtomicInteger();
        var context = ContentExtractionContext.create(() -> consumed.get() >= 128 * 1024, ignored -> { });
        var result = service.extract(ContentExtractionRequest.of(source("txt", bytes, consumed)), context);
        assertEquals(ContentExtractionStatus.CANCELLED, result.status());
        assertTrue(consumed.get() <= 192 * 1024, "Cancellation between bounded chunks");
    }
    @Test
    void preservesTxtParagraphsForAllLineEndingsAndInlineWhitespace() {
        for (String newline : List.of("\n", "\r", "\r\n")) {
            String text = "  а\tб " + newline + "в\u00a0г" + newline + "\t " + newline + "д  ";
            var result = extract("txt", text);
            assertEquals(ContentExtractionStatus.SUCCESS, result.status(), result.message());
            assertEquals("а б в г\nд", result.content().text());
            assertEquals(2, result.content().anchors().size());
        }
    }
    @Test
    void handlesCrLfAcrossReadBufferBoundary() {
        var result = extract("txt", "a".repeat(8191) + "\r\nnext\r\n\r\nlast");
        assertEquals(ContentExtractionStatus.SUCCESS, result.status(), result.message());
        assertEquals("a".repeat(8191) + " next\nlast", result.content().text());
    }
    @Test
    void preservesFb2InlineCdataEmptySectionsAndBodies() {
        var result = extract("fb2", "<FictionBook><body><section/><section><p>а<emphasis>б</emphasis><![CDATA[ в  ]]>&amp; г</p>"
                + "</section></body><body><section><p>д</p></section></body></FictionBook>");
        assertEquals(ContentExtractionStatus.SUCCESS, result.status(), result.message());
        assertEquals("аб в & г\nд", result.content().text());
        assertEquals(2, result.content().chapters().size());
    }
    private ContentExtractionResult extract(String format, String text) {
        return service.extract(ContentExtractionRequest.of(source(format, text.getBytes(StandardCharsets.UTF_8), new AtomicInteger())),
                ContentExtractionContext.none());
    }
    private static ContentExtractionSource source(String format, byte[] bytes, AtomicInteger consumed) {
        return new ContentExtractionSource() {
            public String id() { return "counted"; }
            public String name() { return "counted." + format; }
            public OptionalLong size() { return OptionalLong.empty(); }
            public InputStream openStream() {
                return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                    public int read() throws IOException {
                        int value = in.read();
                        if (value >= 0) consumed.incrementAndGet();
                        return value;
                    }
                    public int read(byte[] b, int off, int len) throws IOException {
                        int read = in.read(b, off, len);
                        if (read > 0) consumed.addAndGet(read);
                        return read;
                    }
                };
            }
        };
    }
}
