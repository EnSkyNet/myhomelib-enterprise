package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class EpubTitleBudgetTest {
    private final ContentExtractionService service = new ContentExtractionService(List.of(new EpubContentExtractor()));

    @Test
    void rejectsOversizedHeadTitleEvenWhenBodyIsTiny() throws Exception {
        var result = extract("t".repeat(64 * 1024 + 1), 1);
        assertEquals(ContentExtractionStatus.FAILED, result.status());
        assertTrue(result.message().contains("title"), result.message());
        assertNull(result.content());
    }

    @Test
    void rejectsCumulativeTitlesAcrossRepeatedSpineReferences() throws Exception {
        // Only one small ZIP entry, reused in the spine: ZIP entry/total-size limits do not bound retained titles.
        var result = extract("t".repeat(64 * 1024), 17);
        assertEquals(ContentExtractionStatus.FAILED, result.status());
        assertTrue(result.message().contains("title"), result.message());
        assertNull(result.content());
    }

    @Test
    void preservesTitlesAndOffsetsAtExactCumulativeBoundary() throws Exception {
        String title = "t".repeat(64 * 1024);
        var result = extract(title, 16);
        assertEquals(ContentExtractionStatus.SUCCESS, result.status(), result.message());
        assertEquals(16, result.content().chapters().size());
        assertEquals(String.join("\n", java.util.Collections.nCopies(16, "x")), result.content().text());
        for (int i = 0; i < 16; i++) {
            var chapter = result.content().chapters().get(i);
            assertEquals(title, chapter.title());
            assertEquals(2L * i, chapter.startOffset());
            assertEquals(2L * i + 1, chapter.endOffset());
        }
    }

    @Test
    void bodyHeadingCannotBypassTitleBudget() throws Exception {
        var result = extract("short", 1, "<h1>" + "h".repeat(64 * 1024 + 1) + "</h1>");
        assertEquals(ContentExtractionStatus.FAILED, result.status());
        assertTrue(result.message().contains("title"), result.message());
        assertNull(result.content());
    }

    private ContentExtractionResult extract(String title, int references) throws IOException {
        return extract(title, references, "<p>x</p>");
    }

    private ContentExtractionResult extract(String title, int references, String body) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setLevel(0); // Isolate title budgets from compression-ratio guards.
            entry(zip, "META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>");
            entry(zip, "content.opf", "<package><manifest><item id=\"c\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine>"
                    + "<itemref idref=\"c\"/>".repeat(references) + "</spine></package>");
            entry(zip, "chapter.xhtml", "<html><head><title>" + title + "</title></head><body>" + body + "</body></html>");
        }
        byte[] data = bytes.toByteArray();
        var source = new ContentExtractionSource() {
            public String id() { return "title-budget"; }
            public String name() { return "title-budget.epub"; }
            public InputStream openStream() { return new ByteArrayInputStream(data); }
            public java.util.OptionalLong size() { return java.util.OptionalLong.of(data.length); }
        };
        return service.extract(ContentExtractionRequest.of(source), ContentExtractionContext.none());
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
