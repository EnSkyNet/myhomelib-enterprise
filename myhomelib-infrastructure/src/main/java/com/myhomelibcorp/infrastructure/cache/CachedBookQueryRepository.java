package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
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

        List<Book> result = new ArrayList<>();
        List<BookId> missingIds = new ArrayList<>();
        for (BookId id : ids) {
            if (id == null) {
                continue;
            }
            Optional<Book> cached = bookCache.get(id);
            if (cached.isPresent()) {
                result.add(cached.get());
            } else {
                missingIds.add(id);
            }
        }
        if (!missingIds.isEmpty()) {
            List<Book> loaded = delegate.findByIds(missingIds);
            for (Book book : loaded) {
                if (book != null && book.getId() != null) {
                    bookCache.put(book.getId(), book);
                }
            }
            result.addAll(loaded);
        }
        return result;
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

    // ===== DataIntegrity =====
    @Override
    public long countBooksWithoutAuthor() {
        return delegate.countBooksWithoutAuthor();
    }

    @Override
    public long countBooksWithoutGenre() {
        return delegate.countBooksWithoutGenre();
    }

    @Override
    public List<BookId> findDuplicateBookIds() {
        return delegate.findDuplicateBookIds();
    }

    // ===== @Deprecated методи =====
    @Override
    @Deprecated
    public List<Book> find(BookQuery query) {
        return delegate.find(query);
    }

    @Override
    @Deprecated
    public List<Book> findAll() {
        return delegate.findAll();
    }
}