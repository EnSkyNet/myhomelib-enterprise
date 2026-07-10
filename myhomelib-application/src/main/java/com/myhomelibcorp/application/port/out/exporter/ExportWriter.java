package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.domain.model.book.Book;

import java.io.OutputStream;
import java.util.Collection;

public interface ExportWriter {
    String getFormatName();
    String getFileExtension();
    void write(Collection<Book> books, OutputStream output);
}