package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.ExportWriter;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

@Component
@Slf4j
public class CsvExportWriter implements ExportWriter {

    @Override
    public String getFormatName() {
        return "CSV";
    }

    @Override
    public String getFileExtension() {
        return "csv";
    }

    @Override
    public void write(Collection<Book> books, OutputStream output) {
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.println("id,title,authors,series,genres,rate,progress");
            for (Book book : books) {
                writer.printf("%s,\"%s\",\"%s\",\"%s\",\"%s\",%d,%d%n",
                        book.getId().asString(),
                        book.getTitle().replace("\"", "\"\""),
                        book.authorsText().replace("\"", "\"\""),
                        book.getSeries() != null ? book.getSeries().replace("\"", "\"\"") : "",
                        book.genresText().replace("\"", "\"\""),
                        book.getRate(),
                        book.getProgress());
            }
            log.info("Exported {} books to CSV", books.size());
        }
    }
}