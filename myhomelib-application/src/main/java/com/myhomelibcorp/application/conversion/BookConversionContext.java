package com.myhomelibcorp.application.conversion;

import com.myhomelibcorp.domain.model.book.Book;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One isolated conversion invocation. The provider never owns the final catalog commit. */
public record BookConversionContext(
        Book book,
        String sourceFormat,
        String targetFormat,
        InputStream sourceStream,
        Path targetFile,
        BooleanSupplier cancelled,
        long maxOutputBytes
) {
    public BookConversionContext {
        Objects.requireNonNull(book, "book");
        sourceFormat = BookConversionCapability.normalizeFormat(sourceFormat);
        targetFormat = BookConversionCapability.normalizeFormat(targetFormat);
        Objects.requireNonNull(sourceStream, "sourceStream");
        Objects.requireNonNull(targetFile, "targetFile");
        cancelled = cancelled == null ? () -> false : cancelled;
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes must be positive");
    }

    public void checkCancelled() {
        if (cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }
}
