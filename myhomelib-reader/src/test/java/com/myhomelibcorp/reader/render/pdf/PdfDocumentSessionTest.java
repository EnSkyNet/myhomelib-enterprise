package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.FileBookSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentSessionTest {
    @TempDir Path tempDir;

    @Test
    void opensPagesRendersRasterAndKeepsCacheBounded() throws Exception {
        Path pdf = tempDir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf, "book-1"))) {
            assertThat(session.pageCount()).isEqualTo(2);
            assertThat(session.sourceId()).isEqualTo("book-1");
            PdfPageSize page = session.pageSize(0);
            assertThat(page.widthPoints()).isGreaterThan(500);
            assertThat(page.heightPoints()).isGreaterThan(700);

            PdfRenderedPage first = session.render(0, 1.0);
            PdfRenderedPage cached = session.render(0, 1.0);
            assertThat(first.width()).isPositive();
            assertThat(first.height()).isPositive();
            assertThat(first.argb()).hasSize(first.width() * first.height());
            assertThat(cached).isSameAs(first);
            assertThat(session.cachedBytes()).isPositive().isLessThanOrEqualTo(64L * 1024L * 1024L);
        }
    }

    @Test
    void rejectsMalformedPdfWithoutLeakingRawRuntimeException() throws Exception {
        Path pdf = tempDir.resolve("broken.pdf");
        Files.writeString(pdf, "this is not a PDF");

        assertThatThrownBy(() -> PdfDocumentSession.open(new FileBookSource(pdf)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsEncryptedPdfGracefully() throws Exception {
        Path pdf = tempDir.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(300, 400)));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> PdfDocumentSession.open(new FileBookSource(pdf)))
                .isInstanceOf(PdfPasswordRequiredException.class);

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf), "user-password")) {
            assertThat(session.pageCount()).isEqualTo(1);
        }
    }

    @Test
    void refusesPathologicalPageThatCannotFitRasterSafetyLimitEvenAtMinimumDpi() throws Exception {
        Path pdf = tempDir.resolve("huge-page.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(100_000, 100_000)));
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf))) {
            assertThatThrownBy(() -> session.render(0, 1.0))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("raster safety limit");
        }
    }

    @Test
    void interruptionCancelsBeforeRenderingStarts() throws Exception {
        Path pdf = tempDir.resolve("interrupt.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> session.render(0, 1.0)).isInstanceOf(InterruptedException.class);
            } finally {
                Thread.interrupted();
            }
        }
    }
    @Test
    void exposesOutlineAndSearchesOnlyThePdfTextLayer() throws Exception {
        Path pdf = tempDir.resolve("searchable.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(first);
            document.addPage(second);
            writeText(document, first, "Alpha chapter has a searchable phrase.");
            writeText(document, second, "Second page repeats SEARCHABLE phrase here.");

            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter = new PDOutlineItem();
            chapter.setTitle("Chapter One");
            chapter.setDestination(first);
            outline.addLast(chapter);
            PDOutlineItem nested = new PDOutlineItem();
            nested.setTitle("Second Page");
            nested.setDestination(second);
            chapter.addLast(nested);
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf))) {
            assertThat(session.outlineEntries())
                    .extracting(PdfOutlineEntry::title, PdfOutlineEntry::pageIndex, PdfOutlineEntry::level)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Chapter One", 0, 0),
                            org.assertj.core.groups.Tuple.tuple("Second Page", 1, 1));

            PdfSearchOutcome outcome = session.searchText("searchable", 10);
            assertThat(outcome.textLayerDetected()).isTrue();
            assertThat(outcome.results()).hasSize(2);
            assertThat(outcome.results()).extracting(PdfSearchResult::pageIndex).containsExactly(0, 1);
            assertThat(outcome.results()).allSatisfy(hit -> assertThat(hit.snippet()).containsIgnoringCase("searchable"));
        }
    }

    @Test
    void imageOnlyOrBlankPdfDegradesToNoTextLayerWithoutOcr() throws Exception {
        Path pdf = tempDir.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf))) {
            PdfSearchOutcome outcome = session.searchText("anything", 10);
            assertThat(outcome.textLayerDetected()).isFalse();
            assertThat(outcome.results()).isEmpty();
        }
    }

    @Test
    void searchHonorsInterruption() throws Exception {
        Path pdf = tempDir.resolve("search-interrupt.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            writeText(document, page, "interruptible text");
            document.save(pdf.toFile());
        }

        try (PdfDocumentSession session = PdfDocumentSession.open(new FileBookSource(pdf))) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> session.searchText("text", 10)).isInstanceOf(InterruptedException.class);
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static void writeText(PDDocument document, PDPage page, String text) throws Exception {
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }

}
