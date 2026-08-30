package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.domain.model.book.Book;

import java.util.Locale;

final class Fb2ConversionSupport {
    private Fb2ConversionSupport() { }

    static boolean supports(Book book) {
        if (book == null) return false;
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }
}
