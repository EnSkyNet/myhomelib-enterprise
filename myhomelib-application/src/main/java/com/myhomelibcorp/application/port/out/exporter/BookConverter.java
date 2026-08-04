package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.domain.model.book.Book;

import java.io.InputStream;
import java.nio.file.Path;

public interface BookConverter {
    boolean supports(Book book);
    String getTargetExtension();
    String getFormatName();
    void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception;
}