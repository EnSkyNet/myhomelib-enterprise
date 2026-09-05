package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;

import java.util.Set;
import java.util.Locale;

public class Fb2Format implements BookFormat {

    private static final Set<String> EXTENSIONS = Set.of("fb2", "fbd");

    @Override
    public String id() {
        return "fb2";
    }

    @Override
    public String displayName() {
        return "FB2";
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public boolean supports(BookSource source) {
        String ext = source.extension();
        return EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    @Override
    public BookParser createParser() {
        return new Fb2StreamingParser();
    }

    @Override
    public boolean isReflowable() {
        return true;
    }
}
