package com.myhomelibcorp.reader.format.txt;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;

import java.util.Locale;
import java.util.Set;

public final class TxtFormat implements BookFormat {
    private static final Set<String> EXTENSIONS = SupportedFormatRegistry.standard().byId("txt").orElseThrow().extensions();

    @Override public String id() { return "txt"; }
    @Override public String displayName() { return "TXT"; }
    @Override public Set<String> extensions() { return EXTENSIONS; }

    @Override
    public boolean supports(BookSource source) {
        return source != null && EXTENSIONS.contains(source.extension().toLowerCase(Locale.ROOT));
    }

    @Override public BookParser createParser() { return new TxtParser(); }
}
