package com.myhomelibcorp.application.content;

/** Internal cooperative-cancellation signal mapped to a typed extraction result. */
public final class ContentExtractionCancelledException extends RuntimeException {
    public ContentExtractionCancelledException() {
        super("Content extraction cancelled");
    }
}
