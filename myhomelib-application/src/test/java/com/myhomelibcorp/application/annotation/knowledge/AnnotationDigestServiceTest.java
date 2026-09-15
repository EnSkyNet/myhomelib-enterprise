package com.myhomelibcorp.application.annotation.knowledge;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationDigestServiceTest {
    @TempDir Path tempDir;

    @Test
    void groupsByBookAndChapterEscapesMarkdownAndWritesStableDeepLinks() throws Exception {
        var port = new RecordingPort(List.of(
                row("a 1", "book/1", "Книга *один*", "Розділ [1]", "цитата *важлива*", "нотатка _тест_", List.of("сюжет", "x_y")),
                row("a-2", "book/1", "Книга *один*", "Розділ [1]", "друга", "", List.of()),
                row("a-3", "book-2", "Друга книга", "", "", "текст", List.of("tag"))));
        var service = new AnnotationDigestService(port);
        Path out = tempDir.resolve("digest.md");

        long count = service.export(AnnotationExportSelection.all(), out,
                new AnnotationDigestLabels("Конспект", "Автор", "Без розділу", "Цитата", "Підсвітка", "Нотатка", "Теги", "Відкрити"));

        String markdown = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(count).isEqualTo(3);
        assertThat(markdown).contains("# Конспект")
                .contains("## Книга \\*один\\*")
                .contains("### Розділ \\[1\\]")
                .contains("**Цитата:** “цитата \\*важлива\\*”")
                .contains("**Нотатка:** нотатка \\_тест\\_")
                .contains("**Теги:** сюжет, x\\_y")
                .contains("myhomelib://book/book%2F1?annotation=a%201")
                .contains("### Без розділу")
                .contains("**Підсвітка**");
        assertThat(port.offsets).containsExactly(0);
    }

    @Test
    void cancellationDoesNotReplaceExistingDigestAndLeavesNoPartFile() throws Exception {
        Path out = tempDir.resolve("digest.md");
        Files.writeString(out, "ORIGINAL", StandardCharsets.UTF_8);
        AnnotationExportQueryPort cancelling = (selection, offset, limit) -> {
            Thread.currentThread().interrupt();
            return new AnnotationExportPage(List.of(row("a-1", "book-1", "Book", "Chapter", "q", "n", List.of())), offset, limit, false);
        };
        var service = new AnnotationDigestService(cancelling);
        try {
            assertThatThrownBy(() -> service.export(AnnotationExportSelection.all(), out))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo("ORIGINAL");
            try (var files = Files.list(tempDir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                        .containsExactly("digest.md");
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static AnnotationExportRow row(String id, String bookId, String title, String chapter,
                                           String quote, String note, List<String> tags) {
        return new AnnotationExportRow(id, bookId, title, "Автор", "", "uk", "book.fb2", "",
                "NOTE", "#fff", "c1", chapter, quote, note, tags, 0.25,
                Instant.parse("2026-09-10T10:15:30Z"), Instant.parse("2026-09-10T11:15:30Z"));
    }

    private static final class RecordingPort implements AnnotationExportQueryPort {
        private final List<AnnotationExportRow> rows;
        private final List<Integer> offsets = new ArrayList<>();

        private RecordingPort(List<AnnotationExportRow> rows) { this.rows = List.copyOf(rows); }

        @Override
        public AnnotationExportPage query(AnnotationExportSelection selection, int offset, int limit) {
            offsets.add(offset);
            int from = Math.min(offset, rows.size());
            int to = Math.min(rows.size(), from + limit);
            return new AnnotationExportPage(rows.subList(from, to), offset, limit, to < rows.size());
        }
    }
}
