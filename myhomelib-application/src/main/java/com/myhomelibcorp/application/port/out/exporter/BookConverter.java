package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.domain.model.book.Book;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * Provider-neutral book conversion SPI.
 *
 * <p>The legacy methods remain part of the contract so existing MyHomeLib external
 * converter adapters keep working. New providers should expose explicit capabilities
 * and may override {@link #convert(BookConversionContext)} to honor cancellation while
 * a long-running engine is active.</p>
 */
public interface BookConverter {
    default String id() { return getClass().getName(); }
    default boolean isAvailable() { return true; }
    boolean supports(Book book);

    /** Source-aware hook for providers whose capability cannot be inferred from Book.file alone. */
    default boolean supports(Book book, String sourceFormat) { return supports(book); }

    String getTargetExtension();
    String getFormatName();

    default Set<BookConversionCapability> capabilities() {
        return Set.of(BookConversionCapability.anySource(getFormatName(), getTargetExtension()));
    }

    /** New job-aware entry point. Legacy providers are adapted automatically. */
    default void convert(BookConversionContext context) throws Exception {
        context.checkCancelled();
        convert(context.book(), context.sourceStream(), context.targetFile());
        context.checkCancelled();
    }

    /** Legacy adapter entry point retained for MHL-505/export compatibility. */
    void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception;
}
