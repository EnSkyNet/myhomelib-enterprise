package com.myhomelibcorp.reader.format.mobi;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;

import java.util.Locale;
import java.util.Set;

/** Built-in Reader support for standard MOBI/PalmDOC containers. */
public final class MobiFormat implements BookFormat {
    private static final Set<String> EXTENSIONS = Set.of("mobi", "prc");

    @Override public String id() { return "mobi"; }
    @Override public String displayName() { return "MOBI"; }
    @Override public Set<String> extensions() { return EXTENSIONS; }

    @Override
    public boolean supports(BookSource source) {
        return source != null && EXTENSIONS.contains(source.extension().toLowerCase(Locale.ROOT));
    }

    @Override public BookParser createParser() { return new MobiParser(); }
}
