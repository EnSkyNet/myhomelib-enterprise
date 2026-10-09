package com.myhomelibcorp.reader.api;

import java.io.IOException;
import java.util.Locale;

public interface BookParser {

    default BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        ReaderDocument document = parse(source, ParseOptions.minimal());
        if (document == null) {
            throw new IOException("Parser returned no document metadata");
        }
        try {
            int chapterCount = document.chapters() == null ? 0 : document.chapters().size();
            return new BookDocumentMetadataSnapshot(
                    document.metadata(),
                    document.totalTextLength(),
                    document.resources() != null && document.resources().count() > 0,
                    chapterCount);
        } finally {
            closeDocumentResources(document);
        }
    }

    private static void closeDocumentResources(ReaderDocument document) {
        if (document != null && document.resources() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Metadata extraction no longer needs document resources. Cleanup is best-effort.
            }
        }
    }

    ReaderDocument parse(BookSource source, ParseOptions options) throws IOException;

    default ReaderDocument parse(BookSource source) throws IOException {
        return parse(source, ParseOptions.defaultOptions());
    }

    default String formatName() {
        return getClass().getSimpleName().replace("Parser", "").toLowerCase(Locale.ROOT);
    }
}
