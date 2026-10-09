package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

@Component
@Slf4j
public class Fb2BookConverter implements BookConverter {

    @Override public int priority() { return 1000; }

    @Override
    public Set<BookConversionCapability> capabilities() {
        return Set.of(new BookConversionCapability(Set.of("fb2"), "FB2", ".fb2"));
    }

    @Override
    public boolean supports(Book book) {
        return Fb2ConversionSupport.supports(book);
    }

    @Override
    public boolean supports(Book book, String sourceFormat) {
        return "fb2".equals(BookConversionCapability.normalizeFormat(sourceFormat));
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
        SafeFilePublication.copyFresh(sourceStream, targetFile, "FB2 copy output");
    }
}
