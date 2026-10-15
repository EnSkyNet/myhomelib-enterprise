package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EpubPackageBudgetTest {
    private static final int METADATA_BYTES = 8 * 1024 * 1024;
    private static final int RECORDS = 100_000;
    private static final String CONTAINER = "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>";
    private static final String ITEM = "<item id=\"c\" href=\"chapter.xhtml\"/>";
    private static final String REF = "<itemref idref=\"c\"/>";
    private final ContentExtractionService service = new ContentExtractionService(List.of(new EpubContentExtractor()));

    @Test
    void rejectsOversizedPackageBeforeParsingIt() throws Exception {
        assertBudgetFailure(extract(CONTAINER, pad(packageXml(ITEM, REF), METADATA_BYTES + 1)), "metadata");
    }

    @Test
    void rejectsOversizedContainerEvenWhenRootfileAppearsFirst() throws Exception {
        assertBudgetFailure(extract(pad(CONTAINER, METADATA_BYTES + 1), packageXml(ITEM, REF)), "metadata");
    }

    @Test
    void countsRepeatedManifestDeclarationsAgainstBudget() throws Exception {
        assertBudgetFailure(extract(CONTAINER, packageXml(ITEM.repeat(RECORDS + 1), REF)), "manifest");
    }

    @Test
    void boundsSpineReferencesEvenWhenTheyDoNotResolveToChapters() throws Exception {
        assertBudgetFailure(extract(CONTAINER, packageXml(ITEM,
                "<itemref idref=\"missing\"/>".repeat(RECORDS + 1))), "spine");
    }

    @Test
    void acceptsMetadataAtExactByteBoundary() throws Exception {
        assertSingleChapter(extract(pad(CONTAINER, METADATA_BYTES), pad(packageXml(ITEM, REF), METADATA_BYTES)));
    }

    @Test
    void acceptsManifestAndSpineAtExactRecordBoundaries() throws Exception {
        assertSingleChapter(extract(CONTAINER, packageXml(ITEM.repeat(RECORDS),
                "<itemref idref=\"missing\"/>".repeat(RECORDS - 1) + REF)));
    }

    @Test
    void cancellationAfterMaterializationPrecedesInvalidPackageParsing() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        byte[] bytes = epub("not XML", packageXml(ITEM, REF));
        var source = source(bytes, cancelled);
        var result = service.extract(ContentExtractionRequest.of(source),
                ContentExtractionContext.create(cancelled, ignored -> { }));
        assertEquals(ContentExtractionStatus.CANCELLED, result.status(), result.message());
        assertNull(result.content());
    }

    private ContentExtractionResult extract(String container, String opf) throws IOException {
        return service.extract(ContentExtractionRequest.of(source(epub(container, opf), null)),
                ContentExtractionContext.none());
    }

    private static void assertBudgetFailure(ContentExtractionResult result, String budget) {
        assertEquals(ContentExtractionStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains(budget), result.message());
        assertTrue(result.message().contains("safety limit"), result.message());
        assertNull(result.content());
    }

    private static void assertSingleChapter(ContentExtractionResult result) {
        assertEquals(ContentExtractionStatus.SUCCESS, result.status(), result.message());
        assertEquals("x", result.content().text());
        assertEquals(1, result.content().chapters().size());
        assertEquals(0, result.content().chapters().getFirst().startOffset());
        assertEquals(1, result.content().chapters().getFirst().endOffset());
    }

    private static String pad(String xml, int size) { return xml + " ".repeat(size - xml.length()); }
    private static String packageXml(String manifest, String spine) {
        return "<package><manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>";
    }

    private static byte[] epub(String container, String opf) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setLevel(0); // Test metadata budgets independently of compression-ratio guards.
            entry(zip, "META-INF/container.xml", container);
            entry(zip, "content.opf", opf);
            entry(zip, "chapter.xhtml", "<html><body><p>x</p></body></html>");
        }
        return bytes.toByteArray();
    }

    private static ContentExtractionSource source(byte[] bytes, AtomicBoolean cancelOnClose) {
        return new ContentExtractionSource() {
            public String id() { return "package-budget"; }
            public String name() { return "package-budget.epub"; }
            public OptionalLong size() { return OptionalLong.of(bytes.length); }
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes) {
                    @Override public void close() throws IOException {
                        super.close();
                        if (cancelOnClose != null) cancelOnClose.set(true);
                    }
                };
            }
        };
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
