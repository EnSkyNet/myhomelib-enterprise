package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;

import java.util.Set;

public class ZipFormat implements BookFormat {

    private static final Set<String> EXTENSIONS = Set.of("zip", "fb2zip", "fb2.zip");

    @Override
    public String id() {
        return "zip";
    }

    @Override
    public String displayName() {
        return "ZIP";
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public boolean supports(BookSource source) {
        String ext = source.extension();
        return EXTENSIONS.contains(ext.toLowerCase());
    }

    @Override
    public BookParser createParser() {
        return new ZipParser();
    }

    @Override
    public boolean isReflowable() {
        return true;
    }
}