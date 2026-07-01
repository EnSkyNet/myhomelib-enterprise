package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedBookQueryRepository implements BookQueryRepository {

    private final BookQueryRepository delegate;
    private final BookCache bookCache;

    @Override
    public Optional<Book> findById(BookId id) {
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
        List<Book> result = new ArrayList<>();
        List<BookId> missingIds = new ArrayList<>();
        for (BookId id : ids) {
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
                bookCache.put(book.getId(), book);
            }
            result.addAll(loaded);
        }
        return result;
    }

    @Override
    public List<Book> find(BookQuery query) {
        // Кешування для складних запитів поки що не реалізовано – делегуємо
        return delegate.find(query);
    }

    @Override
    public long count(BookQuery query) {
        return delegate.count(query);
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        return delegate.findByTitleAndAuthor(title, authorLastName);
    }
}