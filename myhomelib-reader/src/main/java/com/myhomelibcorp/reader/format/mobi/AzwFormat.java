package com.myhomelibcorp.reader.format.mobi;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;

import java.util.Locale;
import java.util.Set;

/** Amazon MOBI/KF8 container extensions handled by the built-in MOBI parser. */
public final class AzwFormat implements BookFormat {
    private static final Set<String> EXTENSIONS = Set.of("azw", "azw3");

    @Override public String id() { return "azw3"; }
    @Override public String displayName() { return "AZW/AZW3"; }
    @Override public Set<String> extensions() { return EXTENSIONS; }

    @Override
    public boolean supports(BookSource source) {
        return source != null && EXTENSIONS.contains(source.extension().toLowerCase(Locale.ROOT));
    }

    @Override public BookParser createParser() { return new MobiParser(); }
}
