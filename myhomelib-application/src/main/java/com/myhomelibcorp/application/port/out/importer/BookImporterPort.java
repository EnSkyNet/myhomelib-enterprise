package com.myhomelibcorp.application.port.out.importer;

import com.myhomelibcorp.domain.model.book.Book;

import java.nio.file.Path;
import java.util.stream.Stream;

public interface BookImporterPort {
    boolean supports(Path file);
    Stream<Book> importBooks(Path file);
    String getFormatName();
    /** Returns the number of importable books, or -1 only when the count cannot be determined safely. */
    long countBooks(Path file);
}