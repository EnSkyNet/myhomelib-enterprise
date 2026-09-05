package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface BookQueryRepository {

    // ===== Пошук з пагінацією =====
    PageResult<Book> findPage(BookQuery query);

    /** Same page contract, but reuses an already known exact total to avoid COUNT(*) on continuation pages. */
    PageResult<Book> findPage(BookQuery query, long knownTotal);

    /**
     * Bidirectional keyset paging for the dominant TITLE sort. The logical page number
     * is still carried by query.pagination().offset(), but OFFSET is not used by SQL.
     */
    PageResult<Book> findTitlePageByCursor(BookQuery query, BookPageCursor cursor,
                                           BookPageDirection pageDirection, long knownTotal);

    long count(BookQuery query);

    /** Count the default visible catalog without materializing rows. */
    default long countAll() { return count(BookQuery.builder().build()); }

    // ===== Пошук по ID =====
    Optional<Book> findById(BookId id);

    List<Book> findByIds(List<BookId> ids);

    /**
     * Lightweight projection for tables/search result lists. Implementations must not
     * populate the full-book cache with these partial Book objects.
     */
    List<Book> findListItemsByIds(List<BookId> ids);


    /**
     * Exact storage lookup used by folder synchronization. Keeping this in the
     * repository avoids loading an entire million-book catalog into memory.
     */
    Optional<Book> findByStorage(String collectionRoot, String folder, String fileName, String archiveEntry);

    /**
     * Returns all catalog rows that point at one physical archive container.
     * relativeArchivePath and absoluteArchivePath are both accepted because
     * legacy/import paths may have been stored in either form.
     */
    List<Book> findByArchiveContainer(String collectionRoot, String relativeArchivePath, String absoluteArchivePath);

    /**
     * Memory-bounded traversal of the complete catalog.
     */
    Stream<Book> streamAll();

    /**
     * Memory-bounded projection dedicated to full-text indexing. Implementations may override
     * this method to avoid constructing full aggregate Book objects and loading fields that
     * Lucene never consumes. The default keeps compatibility with alternate repositories.
     */
    default Stream<BookSnapshot> streamSearchSnapshots() {
        return streamAll()
                .filter(java.util.Objects::nonNull)
                .filter(book -> !book.isDeleted())
                .map(BookSnapshot::fromBook);
    }

    // ===== Спеціальні запити =====
    Optional<Book> findByTitleAndAuthor(String title, String authorLastName);

    List<Book> findRecent(int limit);

    List<Book> findRecentlyAdded(int limit);

    List<Book> findFavoriteAuthors(int limit);


}