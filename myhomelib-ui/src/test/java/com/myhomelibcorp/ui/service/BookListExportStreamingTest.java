package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookListExportStreamingTest {
    private final BookListExportService service = new BookListExportService(null, null, null, null);

    @Test
    void txtFormattingRetainsBookOrderUnicodeAndSeries() throws Exception {
        var books = List.of(
                new BookListExportService.ExportRow("Олена", "Книга", "Серія", "Проза", 5),
                new BookListExportService.ExportRow("Alice", "Untitled", "", "Sci-Fi", 3));
        StringWriter output = new StringWriter();

        service.writeRows("txt", books, output);

        assertThat(output.toString()).isEqualTo(
                "1. Олена — Книга [Серія]\n2. Alice — Untitled\n");
    }

    @Test
    void rtfStreamingPreservesSpecialCharacterEscapingAndTrailer() throws Exception {
        var books = List.of(new BookListExportService.ExportRow(
                "Alice", "Book {One}\\", "", "", 0));
        StringWriter output = new StringWriter();

        service.writeRows("rtf", books, output);

        assertThat(output.toString()).startsWith("{\\rtf1\\ansi\\deff0\\uc1 MyHomeLib\\par ");
        assertThat(output.toString()).contains("Alice - Book \\{One\\}\\\\");
        assertThat(output.toString()).endsWith("\\par }");
    }

    @Test
    void largeExportAppendsIncrementallyInsteadOfOneGiantOutputString() throws Exception {
        var books = new ArrayList<BookListExportService.ExportRow>();
        for (int i = 0; i < 12_000; i++) {
            books.add(new BookListExportService.ExportRow("Author", "Title-" + i, "", "", 0));
        }
        CountingWriter output = new CountingWriter();

        service.writeRows("txt", books, output);

        assertThat(output.chars).isGreaterThan(100_000);
        assertThat(output.calls).isGreaterThan(12_000);
        assertThat(output.maxChunk).isLessThan(1_024);
    }

    private static final class CountingWriter extends Writer {
        private long chars;
        private int calls;
        private int maxChunk;

        @Override public void write(char[] buffer, int offset, int length) {
            chars += length;
            calls++;
            maxChunk = Math.max(maxChunk, length);
        }

        @Override public void flush() throws IOException { }

        @Override public void close() throws IOException { }
    }
}
