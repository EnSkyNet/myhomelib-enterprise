package com.myhomelibcorp.reader.api;

import java.io.IOException;

public interface BookParser {

    BookDocumentMetadata readMetadata(BookSource source) throws IOException;

    ReaderDocument parse(BookSource source, ParseOptions options) throws IOException;

    default ReaderDocument parse(BookSource source) throws IOException {
        return parse(source, ParseOptions.defaultOptions());
    }

    default String formatName() {
        return getClass().getSimpleName().replace("Parser", "").toLowerCase();
    }
}