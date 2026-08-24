package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class EpubBookConverter implements BookConverter {
    @Override public boolean supports(Book book) {
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        return name != null && name.toLowerCase().endsWith(".epub");
    }
    @Override public String getTargetExtension() { return ".epub"; }
    @Override public String getFormatName() { return "EPUB"; }
    @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        Files.copy(sourceStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
