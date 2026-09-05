package com.myhomelibcorp.reader.api;

import java.io.IOException;
import java.util.Locale;

public interface BookParser {

    default BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        ReaderDocument document = parse(source, ParseOptions.minimal());
        return new BookDocumentMetadataSnapshot(
                document.metadata(),
                document.totalTextLength(),
                document.resources() != null && document.resources().count() > 0,
                document.chapters().size());
    }

    ReaderDocument parse(BookSource source, ParseOptions options) throws IOException;

    default ReaderDocument parse(BookSource source) throws IOException {
        return parse(source, ParseOptions.defaultOptions());
    }

    default String formatName() {
        return getClass().getSimpleName().replace("Parser", "").toLowerCase(Locale.ROOT);
    }
}
