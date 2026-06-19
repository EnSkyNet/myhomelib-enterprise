package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.book.Book;

import java.nio.file.Path;
import java.util.List;

public interface BookImporterPort {
    boolean supports(Path file);
    List<Book> importBooks(Path file);
    String getFormatName();
}