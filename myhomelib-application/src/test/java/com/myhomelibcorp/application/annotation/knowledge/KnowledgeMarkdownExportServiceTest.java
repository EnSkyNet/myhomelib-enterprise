package com.myhomelibcorp.application.annotation.knowledge;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeMarkdownExportServiceTest {
    @TempDir Path tempDir;

    @Test
    void exportsOneDeterministicUnicodeMarkdownFilePerBookWithFrontmatterAndBacklinks() throws Exception {
        List<AnnotationExportRow> rows = List.of(
                row("a-1", "book-1", "Книга — Україна", "Автор Один", "Розділ 1", "Цитата ✓\nдругий рядок", "Нотатка 📚"),
                row("a-2", "book-1", "Книга — Україна", "Автор Один", "Розділ 2", "Інша цитата", "ще"),
                row("a-3", "book-2", "Book Two", "Author Two", "Chapter", "quote", "note"));
        RecordingPort port = new RecordingPort(rows);
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(port);
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");

        KnowledgeMarkdownExportResult result = service.export(request(firstRoot,
                KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, true));
        service.export(request(secondRoot, KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, true));

        assertThat(result.writtenBooks()).isEqualTo(2);
        assertThat(result.skippedBooks()).isZero();
        assertThat(result.annotationCount()).isEqualTo(3);
        Path uk = firstRoot.resolve("Автор Один").resolve("Книга — Україна [book-1].md");
        Path uk2 = secondRoot.resolve("Автор Один").resolve("Книга — Україна [book-1].md");
        String text = Files.readString(uk, StandardCharsets.UTF_8);
        assertThat(text).startsWith("---\n")
                .contains("myhomelib_book_id: \"book-1\"")
                .contains("myhomelib_managed: true")
                .contains("<!-- myhomelib-managed: book-id=book-1; export=mhl-509 -->")
                .contains("# Книга — Україна")
                .contains("> Цитата ✓\n> другий рядок")
                .contains("Нотатка 📚")
                .contains("[Open in MyHomeLib](myhomelib://book/book-1?annotation=a-1)");
        assertThat(Files.readAllBytes(uk2)).containsExactly(Files.readAllBytes(uk));
        assertThat(port.requestedLimits).containsOnly(250);
    }

    @Test
    void folderAndFileTemplatesAreSanitizedAndCannotEscapeRoot() throws Exception {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "../Назва:*?", "A/B", "C", "q", "n"))));
        KnowledgeMarkdownExportTemplates templates = new KnowledgeMarkdownExportTemplates(
                "../${authors}/../${series}", "../../${bookTitle}", "${id}\n");
        service.export(new KnowledgeMarkdownExportRequest(AnnotationExportSelection.all(), tempDir, templates,
                false, false, KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED));

        List<Path> files;
        try (var stream = Files.walk(tempDir)) {
            files = stream.filter(Files::isRegularFile).toList();
        }
        assertThat(files).hasSize(1);
        Path target = files.getFirst().toAbsolutePath().normalize();
        assertThat(target).startsWith(tempDir.toAbsolutePath().normalize());
        assertThat(target.getFileName().toString()).doesNotContain("..", ":", "*", "?");
        assertThat(Files.readString(target)).contains("a-1");
    }

    @Test
    void replaceManagedReusesExactPathAndNeverCreatesDuplicateSuffixes() throws Exception {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "Book", "Author", "Chapter", "first", "note"))));
        KnowledgeMarkdownExportRequest request = request(tempDir, KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, false);
        service.export(request);
        Path target = tempDir.resolve("Author").resolve("Book [book-1].md");
        String first = Files.readString(target);

        service.export(request);
        assertThat(Files.readString(target)).isEqualTo(first);
        try (var stream = Files.list(target.getParent())) {
            assertThat(stream.map(p -> p.getFileName().toString()).toList()).containsExactly("Book [book-1].md");
        }
    }

    @Test
    void replaceManagedRefusesToOverwriteUnmanagedUserMarkdown() throws Exception {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "Book", "Author", "Chapter", "q", "n"))));
        Path folder = tempDir.resolve("Author");
        Files.createDirectories(folder);
        Path target = folder.resolve("Book [book-1].md");
        Files.writeString(target, "USER CONTENT", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.export(request(tempDir,
                KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, true)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Refusing to replace unmanaged");
        assertThat(Files.readString(target)).isEqualTo("USER CONTENT");
    }

    @Test
    void skipExistingLeavesTargetUntouchedAndReportsSkippedBook() throws Exception {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "Book", "Author", "Chapter", "q", "n"))));
        Path folder = tempDir.resolve("Author");
        Files.createDirectories(folder);
        Path target = folder.resolve("Book [book-1].md");
        Files.writeString(target, "KEEP", StandardCharsets.UTF_8);

        KnowledgeMarkdownExportResult result = service.export(request(tempDir,
                KnowledgeMarkdownReExportPolicy.SKIP_EXISTING, true, true));
        assertThat(result.writtenBooks()).isZero();
        assertThat(result.skippedBooks()).isEqualTo(1);
        assertThat(result.annotationCount()).isZero();
        assertThat(Files.readString(target)).isEqualTo("KEEP");
    }

    @Test
    void failIfExistsDoesNotModifyExistingTarget() throws Exception {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "Book", "Author", "Chapter", "q", "n"))));
        Path folder = tempDir.resolve("Author");
        Files.createDirectories(folder);
        Path target = folder.resolve("Book [book-1].md");
        Files.writeString(target, "KEEP", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.export(request(tempDir,
                KnowledgeMarkdownReExportPolicy.FAIL_IF_EXISTS, true, true)))
                .isInstanceOf(IOException.class).hasMessageContaining("already exists");
        assertThat(Files.readString(target)).isEqualTo("KEEP");
    }

    @Test
    void keepsBookGroupingCorrectAcrossPageBoundary() throws Exception {
        List<AnnotationExportRow> rows = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            rows.add(row("a-" + i, "book-1", "Book One", "Author", "Chapter", "q" + i, "n"));
        }
        rows.add(row("b-1", "book-2", "Book Two", "Author", "Chapter", "last", "n"));
        RecordingPort port = new RecordingPort(rows);
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(port);

        KnowledgeMarkdownExportResult result = service.export(request(tempDir,
                KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, false, false));

        assertThat(result.writtenBooks()).isEqualTo(2);
        assertThat(result.annotationCount()).isEqualTo(261);
        assertThat(port.requestedOffsets).containsExactly(0, 250);
        String one = Files.readString(tempDir.resolve("Author").resolve("Book One [book-1].md"));
        assertThat(one).contains("q0", "q259");
        assertThat(Files.readString(tempDir.resolve("Author").resolve("Book Two [book-2].md"))).contains("last");
    }

    @Test
    void cancellationDoesNotPublishPartialReplacementOrLeaveStagingFiles() throws Exception {
        AnnotationExportRow value = row("a-1", "book-1", "Book", "Author", "Chapter", "new", "note");
        RecordingPort initialPort = new RecordingPort(List.of(value));
        KnowledgeMarkdownExportService initial = new KnowledgeMarkdownExportService(initialPort);
        initial.export(request(tempDir, KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, true));
        Path target = tempDir.resolve("Author").resolve("Book [book-1].md");
        String original = Files.readString(target);

        AnnotationExportQueryPort cancelling = (selection, offset, limit) -> {
            Thread.currentThread().interrupt();
            return new AnnotationExportPage(List.of(value), offset, limit, false);
        };
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(cancelling);
        try {
            assertThatThrownBy(() -> service.export(request(tempDir,
                    KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED, true, true)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Files.readString(target)).isEqualTo(original);
            try (var stream = Files.walk(tempDir)) {
                assertThat(stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                        .containsExactly("Book [book-1].md");
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unsupportedTemplatePlaceholderFailsClosedBeforeWriting() {
        KnowledgeMarkdownExportService service = new KnowledgeMarkdownExportService(new RecordingPort(List.of(
                row("a-1", "book-1", "Book", "Author", "Chapter", "q", "n"))));
        KnowledgeMarkdownExportTemplates bad = new KnowledgeMarkdownExportTemplates(
                "${authors}", "${unknown}", "${id}");
        assertThatThrownBy(() -> service.export(new KnowledgeMarkdownExportRequest(
                AnnotationExportSelection.all(), tempDir, bad, true, true,
                KnowledgeMarkdownReExportPolicy.REPLACE_MANAGED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported knowledge export placeholder");
    }

    private static KnowledgeMarkdownExportRequest request(Path root, KnowledgeMarkdownReExportPolicy policy,
                                                           boolean frontmatter, boolean backlinks) {
        return new KnowledgeMarkdownExportRequest(AnnotationExportSelection.all(), root,
                KnowledgeMarkdownExportTemplates.defaults(), frontmatter, backlinks, policy);
    }

    private static AnnotationExportRow row(String id, String bookId, String title, String authors,
                                           String chapter, String quote, String note) {
        return new AnnotationExportRow(id, bookId, title, authors, "Series", "uk", "book.fb2", "978-0",
                "NOTE", "#fff", "chapter-1", chapter, quote, note, List.of("тег", "alpha"), 0.25,
                Instant.parse("2026-09-10T10:15:30Z"), Instant.parse("2026-09-10T11:15:30Z"));
    }

    private static final class RecordingPort implements AnnotationExportQueryPort {
        private final List<AnnotationExportRow> rows;
        private final List<Integer> requestedOffsets = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();

        private RecordingPort(List<AnnotationExportRow> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public AnnotationExportPage query(AnnotationExportSelection selection, int offset, int limit) {
            requestedOffsets.add(offset);
            requestedLimits.add(limit);
            int from = Math.min(offset, rows.size());
            int to = Math.min(rows.size(), from + limit);
            return new AnnotationExportPage(rows.subList(from, to), offset, limit, to < rows.size());
        }
    }
}
