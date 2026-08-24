package com.myhomelibcorp.reader.format.epub;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;

import java.util.Locale;
import java.util.Set;

public final class EpubFormat implements BookFormat {
    private static final Set<String> EXTENSIONS = Set.of("epub");

    @Override public String id() { return "epub"; }
    @Override public String displayName() { return "EPUB"; }
    @Override public Set<String> extensions() { return EXTENSIONS; }

    @Override
    public boolean supports(BookSource source) {
        return source != null && "epub".equals(source.extension().toLowerCase(Locale.ROOT));
    }

    @Override public BookParser createParser() { return new EpubParser(); }
}
