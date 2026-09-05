package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Locale;

@Component
@Slf4j
public class Fb2ZipBookConverter implements BookConverter {

    @Override
    public boolean supports(Book book) {
        return Fb2ConversionSupport.supports(book);
    }

    @Override
    public String getTargetExtension() {
        return ".fb2.zip";
    }

    @Override
    public String getFormatName() {
        return "FB2 ZIP";
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        log.debug("Архівація FB2: {}", book.getTitle());

        String entryName = book.getArchiveEntry();
        if (entryName == null || entryName.isBlank()) entryName = book.getFileName();
        if (!entryName.toLowerCase(Locale.ROOT).endsWith(".fb2") && !entryName.toLowerCase(Locale.ROOT).endsWith(".fbd")) {
            entryName = book.getTitle() + ".fb2";
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetFile))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            sourceStream.transferTo(zos);
            zos.closeEntry();
        }

        log.info("FB2 ZIP створено: {}", targetFile);
    }
}
