package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@Slf4j
public class Fb2BookConverter implements BookConverter {

    @Override
    public boolean supports(Book book) {
        return Fb2ConversionSupport.supports(book);
    }

    @Override
    public String getTargetExtension() {
        return ".fb2";
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        log.debug("Копіювання FB2: {} -> {}", book.getTitle(), targetFile);
        Files.copy(sourceStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
}