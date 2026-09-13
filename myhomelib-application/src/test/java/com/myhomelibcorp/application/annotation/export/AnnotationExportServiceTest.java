package com.myhomelibcorp.application.annotation.export;

import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationExportServiceTest {
    @TempDir Path tempDir;

    @Test
    void streamsDeterministicUtf8JsonWithVersionedSchemaAndStableIdentity() throws Exception {
        List<AnnotationExportRow> rows = List.of(
                row("a-1", "book-1", "Книга — Україна", "Цитата \"раз\"\nдва", "Нотатка ✓ 📚", List.of("α", "тег")),
                row("a-2", "book-2", "Book <Two>", "quote", "note", List.of("beta")));
        RecordingPort port = new RecordingPort(rows);
        AnnotationExportService service = new AnnotationExportService(port);
        Path first = tempDir.resolve("first.json");
        Path second = tempDir.resolve("second.json");
        AnnotationExportSelection selection = AnnotationExportSelection.books(Set.of("book-2", "book-1"));

        AnnotationExportResult result = service.export(new AnnotationExportRequest(
                AnnotationExportFormat.JSON, selection, first, AnnotationExportTemplates.defaults()));
        service.export(new AnnotationExportRequest(
                AnnotationExportFormat.JSON, selection, second, AnnotationExportTemplates.defaults()));

        String json = Files.readString(first, StandardCharsets.UTF_8);
        assertThat(result.annotationCount()).isEqualTo(2);
        assertThat(json).contains("\"schema\": \"myhomelib.annotations.export/v1\"")
                .contains("\"id\":\"a-1\"")
                .contains("Книга — Україна")
                .contains("Нотатка ✓ 📚")
                .contains("Цитата \\\"раз\\\"\\nдва")
                .contains("\"tags\":[\"α\",\"тег\"]");
        assertThat(Files.readAllBytes(second)).containsExactly(Files.readAllBytes(first));
        assertThat(port.requestedLimits).allMatch(limit -> limit > 0 && limit <= 250);
        assertThat(port.selections).allMatch(value -> value.bookIds().equals(Set.of("book-1", "book-2")));
    }

    @Test
    void appliesMarkdownAndHtmlTemplatesWithFormatAppropriateEscaping() throws Exception {
        AnnotationExportRow value = row("a-1", "book-1", "A <B> & C", "*quoted* <x>", "note & <tag>", List.of("x_y"));
        AnnotationExportService service = new AnnotationExportService(new RecordingPort(List.of(value)));
        AnnotationExportTemplates templates = new AnnotationExportTemplates(
                "HEAD\n${items}TAIL", "${id}|${bookTitle}|${quote}|${note}|${tags}\n",
                "<main>${items}</main>", "<article data-id=\"${id}\">${bookTitle}|${quote}|${note}|${tags}</article>");

        Path markdown = tempDir.resolve("annotations.md");
        Path html = tempDir.resolve("annotations.html");
        service.export(new AnnotationExportRequest(AnnotationExportFormat.MARKDOWN,
                AnnotationExportSelection.books(Set.of("book-1")), markdown, templates));
        service.export(new AnnotationExportRequest(AnnotationExportFormat.HTML,
                AnnotationExportSelection.all(), html, templates));

        assertThat(Files.readString(markdown, StandardCharsets.UTF_8))
                .isEqualTo("HEAD\na-1|A &lt;B&gt; & C|\\*quoted\\* &lt;x&gt;|note & &lt;tag&gt;|x\\_y\nTAIL");
        assertThat(Files.readString(html, StandardCharsets.UTF_8))
                .isEqualTo("<main><article data-id=\"a-1\">A &lt;B&gt; &amp; C|*quoted* &lt;x&gt;|note &amp; &lt;tag&gt;|x_y</article></main>");
    }


    @Test
    void templateRenderingDoesNotReinterpretPlaceholderLikeTextInsideAnnotationContent() throws Exception {
        AnnotationExportRow value = row("a-1", "book-1", "Book", "literal ${note}", "actual note", List.of());
        AnnotationExportTemplates templates = new AnnotationExportTemplates(
                "${items}", "${quote}|${note}", "<main>${items}</main>", "<p>${quote}|${note}</p>");
        AnnotationExportService service = new AnnotationExportService(new RecordingPort(List.of(value)));

        Path markdown = tempDir.resolve("placeholder-content.md");
        service.export(new AnnotationExportRequest(AnnotationExportFormat.MARKDOWN,
                AnnotationExportSelection.all(), markdown, templates));

        assertThat(Files.readString(markdown, StandardCharsets.UTF_8))
                .isEqualTo("literal ${note}|actual note");
    }

    @Test
    void normalizedDocumentTemplateAlwaysKeepsAnItemsInsertionPoint() throws Exception {
        AnnotationExportRow value = row("a-1", "book-1", "Book", "quote", "note", List.of());
        AnnotationExportTemplates templates = new AnnotationExportTemplates(
                "HEADER", "${id}", "<html><body>HEADER</body></html>", "<p>${id}</p>");
        AnnotationExportService service = new AnnotationExportService(new RecordingPort(List.of(value)));

        Path markdown = tempDir.resolve("no-marker.md");
        Path html = tempDir.resolve("no-marker.html");
        service.export(new AnnotationExportRequest(AnnotationExportFormat.MARKDOWN,
                AnnotationExportSelection.all(), markdown, templates));
        service.export(new AnnotationExportRequest(AnnotationExportFormat.HTML,
                AnnotationExportSelection.all(), html, templates));

        assertThat(Files.readString(markdown, StandardCharsets.UTF_8)).isEqualTo("HEADER\na-1");
        assertThat(Files.readString(html, StandardCharsets.UTF_8))
                .isEqualTo("<html><body>HEADER<p>a-1</p></body></html>");
    }

    @Test
    void pagesThroughLargeExportInsteadOfRequestingAllRowsAtOnce() throws Exception {
        List<AnnotationExportRow> rows = new ArrayList<>();
        for (int i = 0; i < 520; i++) rows.add(row("a-" + i, "book-1", "Book", "q" + i, "n" + i, List.of()));
        RecordingPort port = new RecordingPort(rows);
        AnnotationExportService service = new AnnotationExportService(port);

        AnnotationExportResult result = service.export(new AnnotationExportRequest(
                AnnotationExportFormat.JSON, AnnotationExportSelection.all(), tempDir.resolve("large.json"), null));

        assertThat(result.annotationCount()).isEqualTo(520);
        assertThat(port.requestedOffsets).containsExactly(0, 250, 500);
        assertThat(port.requestedLimits).containsOnly(250);
    }

    @Test
    void cancellationNeverPublishesPartialReplacement() throws Exception {
        Path destination = tempDir.resolve("annotations.json");
        Files.writeString(destination, "ORIGINAL", StandardCharsets.UTF_8);
        AnnotationExportQueryPort cancelling = (selection, offset, limit) -> {
            Thread.currentThread().interrupt();
            return new AnnotationExportPage(List.of(row("a-1", "book-1", "Book", "q", "n", List.of())), offset, limit, false);
        };
        AnnotationExportService service = new AnnotationExportService(cancelling);

        try {
            assertThatThrownBy(() -> service.export(new AnnotationExportRequest(
                    AnnotationExportFormat.JSON, AnnotationExportSelection.all(), destination, null)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Files.readString(destination, StandardCharsets.UTF_8)).isEqualTo("ORIGINAL");
            try (var files = Files.list(tempDir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                        .containsExactly("annotations.json");
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static AnnotationExportRow row(String id, String bookId, String title, String quote, String note, List<String> tags) {
        return new AnnotationExportRow(id, bookId, title, "Автор", "Series", "uk", "book.fb2", "isbn",
                "NOTE", "#fff", "chapter-1", "Розділ 1", quote, note, tags, 0.25,
                Instant.parse("2026-09-10T10:15:30Z"), Instant.parse("2026-09-10T11:15:30Z"));
    }

    private static final class RecordingPort implements AnnotationExportQueryPort {
        private final List<AnnotationExportRow> rows;
        private final List<Integer> requestedOffsets = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<AnnotationExportSelection> selections = new ArrayList<>();

        private RecordingPort(List<AnnotationExportRow> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public AnnotationExportPage query(AnnotationExportSelection selection, int offset, int limit) {
            requestedOffsets.add(offset);
            requestedLimits.add(limit);
            selections.add(selection);
            int from = Math.min(offset, rows.size());
            int to = Math.min(rows.size(), from + limit);
            return new AnnotationExportPage(rows.subList(from, to), offset, limit, to < rows.size());
        }
    }
}
