package com.myhomelibcorp.infrastructure.exporter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.stereotype.Component;
@Component public class EpubExternalFromFb2Converter extends ExternalCommandBookConverter {
    public EpubExternalFromFb2Converter(ApplicationSettingsPort s) { super(s, "converter.epub.command", ".epub", "EPUB-CONVERT"); }
    @Override public boolean supports(Book book) {
        String n = book.getArchiveEntry(); if (n == null || n.isBlank()) n = book.getFileName();
        n = n == null ? "" : n.toLowerCase();
        return isAvailable() && (n.endsWith(".fb2") || n.endsWith(".fbd"));
    }
}
