package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedBookQueryRepository implements BookQueryRepository {

    private final BookQueryRepository delegate;
    private final BookCache bookCache;

    // ===== Пагінація (делегуємо без кешу) =====
    @Override
    public PageResult<Book> findPage(BookQuery query) {
        return delegate.findPage(query);
    }

    @Override
    public PageResult<Book> findPage(BookQuery query, long knownTotal) {
        return delegate.findPage(query, knownTotal);
    }

    @Override
    public PageResult<Book> findTitlePageByCursor(BookQuery query, BookPageCursor cursor,
                                                  BookPageDirection pageDirection, long knownTotal) {
        return delegate.findTitlePageByCursor(query, cursor, pageDirection, knownTotal);
    }

    @Override
    public long count(BookQuery query) {
        return delegate.count(query);
    }

    // ===== Пошук по ID (з кешем) =====
    @Override
    public Optional<Book> findById(BookId id) {
        if (id == null) {
            log.debug("Спроба пошуку книги з null ID");
            return Optional.empty();
        }

        Optional<Book> cached = bookCache.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Book> book = delegate.findById(id);
        book.ifPresent(b -> bookCache.put(id, b));
        return book;
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        // Keep repository results stable regardless of which requested IDs happened to be cached.
        // The previous implementation appended cache hits first and DB misses afterwards, so the
        // same request could return a different order after the cache warmed up.
        java.util.LinkedHashMap<BookId, Book> resolved = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<BookId> missingIds = new java.util.LinkedHashSet<>();
        for (BookId id : ids) {
            if (id == null || resolved.containsKey(id)) {
                continue;
            }
            Optional<Book> cached = bookCache.get(id);
            if (cached.isPresent()) {
                resolved.put(id, cached.get());
            } else {
                missingIds.add(id);
            }
        }
        if (!missingIds.isEmpty()) {
            List<Book> loaded = delegate.findByIds(new ArrayList<>(missingIds));
            for (Book book : loaded) {
                if (book != null && book.getId() != null) {
                    resolved.put(book.getId(), book);
                    bookCache.put(book.getId(), book);
                }
            }
        }

        List<Book> result = new ArrayList<>(ids.size());
        for (BookId id : ids) {
            if (id == null) continue;
            Book book = resolved.get(id);
            if (book != null) result.add(book);
        }
        return result;
    }

    @Override
    public List<Book> findListItemsByIds(List<BookId> ids) {
        // Deliberately bypass the full-book cache: list projections omit large/details fields.
        return delegate.findListItemsByIds(ids);
    }

    @Override
    public Optional<Book> findByStorage(String collectionRoot, String folder, String fileName, String archiveEntry) {
        return delegate.findByStorage(collectionRoot, folder, fileName, archiveEntry);
    }

    @Override
    public List<Book> findByArchiveContainer(String collectionRoot, String relativeArchivePath, String absoluteArchivePath) {
        return delegate.findByArchiveContainer(collectionRoot, relativeArchivePath, absoluteArchivePath);
    }

    @Override
    public Stream<Book> streamAll() {
        return delegate.streamAll();
    }

    // ===== Спеціальні запити (делегуємо) =====
    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        return delegate.findByTitleAndAuthor(title, authorLastName);
    }

    @Override
    public List<Book> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public List<Book> findRecentlyAdded(int limit) {
        return delegate.findRecentlyAdded(limit);
    }

    @Override
    public List<Book> findFavoriteAuthors(int limit) {
        return delegate.findFavoriteAuthors(limit);
    }


}