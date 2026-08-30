package com.myhomelibcorp.application.port.out.search;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.application.search.SearchIndexProgress;
import com.myhomelibcorp.application.search.SearchIndexPerformanceReport;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public interface SearchIndexer {
    void indexBook(Book book);
    void indexSnapshot(BookSnapshot snapshot);
    void indexAll(List<Book> books);
    void deleteBook(BookId bookId);
    void rebuildIndex();

    /** Cancellation-aware rebuild. Implementations must keep the previous committed index on failure/cancel. */
    void rebuildIndex(AtomicBoolean cancelFlag, Consumer<SearchIndexProgress> progressListener);

    /** Start a Lucene mutation that must become visible atomically at the final commit. */
    void beginAtomicUpdate();

    /** Discard all changes made since beginAtomicUpdate()/the last committed point. */
    void rollbackAtomicUpdate();

    int getDocumentCount();

    /** Last completed full-rebuild performance telemetry. */
    Optional<SearchIndexPerformanceReport> lastPerformanceReport();

    /**
     * Явний commit змін до індексу.
     * Після виклику індекс стає видимим для пошуку.
     */
    void commit();
}